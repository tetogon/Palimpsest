//! Vault state as a pure function of its operations (spec §7): order-, duplicate- and
//! purge-independent, so every device with the same blocks computes the same state.

use crate::format::Id;
use crate::op::Op;
use std::collections::{BTreeSet, HashMap, HashSet};

pub struct EntryHeads {
    /// Head shown by default: max (hlc, session_id) among PUT heads.
    pub head: Id,
    /// Other concurrent PUT heads; non-empty means a conflict.
    pub others: Vec<Id>,
}

#[derive(Default)]
pub struct State {
    pub entries: HashMap<Id, EntryHeads>,
    pub dead: HashSet<Id>,
    pub max_hlc: u64,
    heads: HashMap<Id, Vec<Id>>,
}

impl State {
    pub fn compute(ops: &HashMap<Id, Op>) -> State {
        let mut by_entry: HashMap<Id, Vec<&Op>> = HashMap::new();
        for op in ops.values() {
            by_entry.entry(op.entry_id()).or_default().push(op);
        }
        let mut s = State { max_hlc: ops.values().map(Op::hlc).max().unwrap_or(0), ..State::default() };
        for (entry_id, list) in by_entry {
            let superseded: HashSet<Id> = list.iter().flat_map(|o| o.supersedes().iter().copied()).collect();
            let heads: Vec<&Op> = list.iter().copied().filter(|o| !superseded.contains(&o.op_id())).collect();
            // Tombstones never die; only replaced content does (spec §8.1).
            for o in &list {
                if matches!(o, Op::Put(_)) && superseded.contains(&o.op_id()) {
                    s.dead.insert(o.op_id());
                }
            }
            let mut put_heads: Vec<(u64, Id, Id)> = heads
                .iter()
                .filter_map(|o| match o {
                    Op::Put(p) => Some((p.hlc, p.session_id, p.op_id)),
                    Op::Delete(_) => None,
                })
                .collect();
            put_heads.sort_by(|a, b| (b.0, b.1).cmp(&(a.0, a.1)));
            if let Some((_, _, first)) = put_heads.first() {
                s.entries.insert(
                    entry_id,
                    EntryHeads { head: *first, others: put_heads[1..].iter().map(|h| h.2).collect() },
                );
            }
            s.heads.insert(entry_id, heads.iter().map(|o| o.op_id()).collect());
        }
        s
    }

    /// `supersedes` for a new operation that replaces every current head of the entry.
    pub fn supersedes_for(&self, entry_id: &Id, ops: &HashMap<Id, Op>) -> BTreeSet<Id> {
        let mut out = BTreeSet::new();
        for h in self.heads.get(entry_id).into_iter().flatten() {
            if let Some(op) = ops.get(h) {
                out.extend(op.supersedes().iter().copied());
                out.insert(*h);
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::random_bytes;
    use crate::op::{DeleteOp, PutOp};

    fn id() -> Id {
        random_bytes(16).try_into().unwrap()
    }

    struct Log {
        entry: Id,
        ops: HashMap<Id, Op>,
    }

    impl Log {
        fn new() -> Log {
            Log { entry: id(), ops: HashMap::new() }
        }

        fn ancestry(&self, parents: &[Id]) -> BTreeSet<Id> {
            let mut s = BTreeSet::new();
            for p in parents {
                s.extend(self.ops[p].supersedes().iter().copied());
                s.insert(*p);
            }
            s
        }

        fn put(&mut self, hlc: u64, parents: &[Id]) -> Id {
            let op = PutOp {
                op_id: id(),
                hlc,
                session_id: id(),
                entry_id: self.entry,
                supersedes: self.ancestry(parents),
                entry_type: 1,
                created_ms: 0,
                diary_date: None,
                title: format!("t{hlc}"),
                body: String::new(),
                tags: vec![],
                unknown: vec![],
            };
            let op_id = op.op_id;
            self.ops.insert(op_id, Op::Put(op));
            op_id
        }

        fn del(&mut self, hlc: u64, parents: &[Id]) -> Id {
            let op = DeleteOp { op_id: id(), hlc, session_id: id(), entry_id: self.entry, supersedes: self.ancestry(parents) };
            let op_id = op.op_id;
            self.ops.insert(op_id, Op::Delete(op));
            op_id
        }
    }

    #[test]
    fn edit_supersedes_and_marks_dead() {
        let mut l = Log::new();
        let a = l.put(1, &[]);
        let b = l.put(2, &[a]);
        let s = State::compute(&l.ops);
        assert_eq!(s.entries[&l.entry].head, b);
        assert_eq!(s.dead, [a].into());
    }

    #[test]
    fn concurrent_edits_are_a_conflict() {
        let mut l = Log::new();
        let a = l.put(1, &[]);
        l.put(2, &[a]);
        l.put(3, &[a]);
        assert_eq!(State::compute(&l.ops).entries[&l.entry].others.len(), 1);
    }

    #[test]
    fn resurrected_ancestor_stays_dead_after_purge() {
        let mut l = Log::new();
        let a = l.put(1, &[]);
        let b = l.put(2, &[a]);
        let c = l.put(3, &[b]);
        l.ops.remove(&b); // purged; a comes back from an old copy
        let s = State::compute(&l.ops);
        assert_eq!(s.entries[&l.entry].head, c);
        assert!(s.dead.contains(&a));
    }

    #[test]
    fn delete_removes_entry_but_concurrent_edit_wins() {
        let mut l = Log::new();
        let a = l.put(1, &[]);
        l.del(2, &[a]);
        assert!(!State::compute(&l.ops).entries.contains_key(&l.entry));
        l.put(3, &[a]);
        assert!(State::compute(&l.ops).entries.contains_key(&l.entry));
    }
}
