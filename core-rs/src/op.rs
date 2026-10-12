//! One operation of a vault's log (spec §6.2) and its binary encoding.

use crate::format::Id;
use crate::tlv::{self, Field};
use std::collections::BTreeSet;
use zeroize::Zeroize;

const VERSION: u8 = 1;
const TYPE_PUT: u8 = 0x01;
const TYPE_DELETE: u8 = 0x02;
const HEADER: usize = 1 + 1 + 8 + 16;

const T_ENTRY_ID: u8 = 0x01;
const T_SUPERSEDES: u8 = 0x02;
const T_ENTRY_TYPE: u8 = 0x03;
const T_CREATED: u8 = 0x04;
const T_DIARY_DATE: u8 = 0x05;
const T_TITLE: u8 = 0x06;
const T_BODY: u8 = 0x07;
const T_TAG: u8 = 0x08;
const T_ATTACHMENT: u8 = 0x09;

const REPEATABLE: [u8; 2] = [T_TAG, T_ATTACHMENT];
const KNOWN_PUT: [u8; 8] = [T_ENTRY_ID, T_SUPERSEDES, T_ENTRY_TYPE, T_CREATED, T_DIARY_DATE, T_TITLE, T_BODY, T_TAG];

#[derive(Clone)]
pub struct PutOp {
    pub op_id: Id,
    pub hlc: u64,
    pub session_id: Id,
    pub entry_id: Id,
    /// Every ancestor revision this operation replaces (spec §7.1).
    pub supersedes: BTreeSet<Id>,
    pub entry_type: u8,
    pub created_ms: u64,
    pub diary_date: Option<u32>,
    pub title: String,
    pub body: String,
    pub tags: Vec<String>,
    /// Fields from newer versions, preserved verbatim on rewrite.
    pub unknown: Vec<Field>,
}

impl Drop for PutOp {
    fn drop(&mut self) {
        self.title.zeroize();
        self.body.zeroize();
        self.tags.iter_mut().for_each(Zeroize::zeroize);
    }
}

#[derive(Clone)]
pub struct DeleteOp {
    pub op_id: Id,
    pub hlc: u64,
    pub session_id: Id,
    pub entry_id: Id,
    pub supersedes: BTreeSet<Id>,
}

#[derive(Clone)]
pub enum Op {
    Put(PutOp),
    Delete(DeleteOp),
}

impl Op {
    pub fn op_id(&self) -> Id {
        match self {
            Op::Put(p) => p.op_id,
            Op::Delete(d) => d.op_id,
        }
    }

    pub fn hlc(&self) -> u64 {
        match self {
            Op::Put(p) => p.hlc,
            Op::Delete(d) => d.hlc,
        }
    }

    pub fn entry_id(&self) -> Id {
        match self {
            Op::Put(p) => p.entry_id,
            Op::Delete(d) => d.entry_id,
        }
    }

    pub fn supersedes(&self) -> &BTreeSet<Id> {
        match self {
            Op::Put(p) => &p.supersedes,
            Op::Delete(d) => &d.supersedes,
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        let (ty, hlc, session_id, entry_id, supersedes) = match self {
            Op::Put(p) => (TYPE_PUT, p.hlc, p.session_id, p.entry_id, &p.supersedes),
            Op::Delete(d) => (TYPE_DELETE, d.hlc, d.session_id, d.entry_id, &d.supersedes),
        };
        let mut out = Vec::with_capacity(HEADER + 64);
        out.push(VERSION);
        out.push(ty);
        out.extend_from_slice(&hlc.to_le_bytes());
        out.extend_from_slice(&session_id);

        let mut w = tlv::Writer::default();
        w.put(T_ENTRY_ID, &entry_id);
        w.put(T_SUPERSEDES, &supersedes.iter().flatten().copied().collect::<Vec<u8>>());
        if let Op::Put(p) = self {
            w.put_u8(T_ENTRY_TYPE, p.entry_type);
            w.put_u64(T_CREATED, p.created_ms);
            if let Some(d) = p.diary_date {
                w.put_u32(T_DIARY_DATE, d);
            }
            w.put(T_TITLE, p.title.as_bytes());
            w.put(T_BODY, p.body.as_bytes());
            for t in &p.tags {
                w.put(T_TAG, t.as_bytes());
            }
            for f in &p.unknown {
                w.put(f.tag, &f.value);
            }
        }
        out.extend_from_slice(&w.into_bytes());
        out
    }

    /// None for anything malformed; the caller treats the object as foreign.
    pub fn decode(op_id: Id, data: &[u8]) -> Option<Op> {
        if data.len() < HEADER || data[0] != VERSION {
            return None;
        }
        let ty = data[1];
        let hlc = u64::from_le_bytes(data[2..10].try_into().ok()?);
        let session_id: Id = data[10..26].try_into().ok()?;
        let fields = tlv::parse(data, HEADER)?;

        let mut seen = BTreeSet::new();
        for f in &fields {
            if !REPEATABLE.contains(&f.tag) && !seen.insert(f.tag) {
                return None;
            }
        }
        let single = |tag: u8| fields.iter().find(|f| f.tag == tag).map(|f| f.value.as_slice());

        let entry_id: Id = single(T_ENTRY_ID)?.try_into().ok()?;
        let supersedes = parse_ids(single(T_SUPERSEDES)?)?;
        if supersedes.contains(&op_id) {
            return None;
        }

        match ty {
            TYPE_PUT => {
                let entry_type = match single(T_ENTRY_TYPE)? {
                    [t] => *t,
                    _ => return None,
                };
                let created_ms = u64::from_le_bytes(single(T_CREATED)?.try_into().ok()?);
                let diary_date = match single(T_DIARY_DATE) {
                    None => None,
                    Some(b) => Some(u32::from_le_bytes(b.try_into().ok()?)),
                };
                let utf8 = |tag: u8| -> Option<String> {
                    match single(tag) {
                        None => Some(String::new()),
                        Some(b) => String::from_utf8(b.to_vec()).ok(),
                    }
                };
                let title = utf8(T_TITLE)?;
                let body = utf8(T_BODY)?;
                let mut tags = Vec::new();
                for f in fields.iter().filter(|f| f.tag == T_TAG) {
                    tags.push(String::from_utf8(f.value.clone()).ok()?);
                }
                let unknown = fields.iter().filter(|f| !KNOWN_PUT.contains(&f.tag)).cloned().collect();
                Some(Op::Put(PutOp {
                    op_id,
                    hlc,
                    session_id,
                    entry_id,
                    supersedes,
                    entry_type,
                    created_ms,
                    diary_date,
                    title,
                    body,
                    tags,
                    unknown,
                }))
            }
            TYPE_DELETE => Some(Op::Delete(DeleteOp { op_id, hlc, session_id, entry_id, supersedes })),
            _ => None,
        }
    }
}

/// Concatenated 16-byte ids, strictly ascending (canonical, no duplicates).
fn parse_ids(b: &[u8]) -> Option<BTreeSet<Id>> {
    if b.len() % 16 != 0 {
        return None;
    }
    let ids: Vec<Id> = b.chunks(16).map(|c| c.try_into().unwrap()).collect();
    if ids.windows(2).any(|w| w[0] >= w[1]) {
        return None;
    }
    Some(ids.into_iter().collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::random_bytes;

    fn id() -> Id {
        random_bytes(16).try_into().unwrap()
    }

    #[test]
    fn round_trip_preserves_unknown_fields() {
        let op = Op::Put(PutOp {
            op_id: id(),
            hlc: 42,
            session_id: id(),
            entry_id: id(),
            supersedes: [id(), id()].into_iter().collect(),
            entry_type: 1,
            created_ms: 1000,
            diary_date: Some(19_000),
            title: "Заголовок".into(),
            body: "Тело ✓".into(),
            tags: vec!["a".into(), "b".into()],
            unknown: vec![Field { tag: 0x30, value: vec![9] }],
        });
        let Some(Op::Put(back)) = Op::decode(op.op_id(), &op.encode()) else { panic!() };
        let Op::Put(orig) = &op else { unreachable!() };
        assert_eq!(back.title, orig.title);
        assert_eq!(back.body, orig.body);
        assert_eq!(back.supersedes, orig.supersedes);
        assert_eq!(back.tags, orig.tags);
        assert_eq!(back.unknown[0].tag, 0x30);
    }

    #[test]
    fn rejects_self_supersede() {
        let op_id = id();
        let op = Op::Delete(DeleteOp { op_id, hlc: 1, session_id: id(), entry_id: id(), supersedes: [op_id].into() });
        assert!(Op::decode(op_id, &op.encode()).is_none());
    }

    #[test]
    fn random_input_never_panics() {
        for n in 0..3000 {
            let _ = Op::decode(id(), &random_bytes(n % 300));
        }
    }
}
