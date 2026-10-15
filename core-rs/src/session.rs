//! An unlocked vault, from unlock until close.

use crate::attachment::{self, AttachmentRef};
use crate::chaff;
use crate::container::{append_shuffled, chaff_blocks, guard, CoreError, Result, Shared};
use crate::crypto::{self, Key};
use crate::format::*;
use crate::frame::{Assembler, Frame, Kind};
use crate::heap::Heap;
use crate::keys::{locator, BlockCipher, VaultKeys};
use crate::op::{DeleteOp, Op, PutOp};
use crate::state::State;
use crate::tlv::Field;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use zeroize::Zeroizing;

/// The three sections of the app. Unknown entry types from newer versions show as notes.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum EntryKind {
    Note,
    Diary,
    Todo,
}

impl EntryKind {
    fn from_type(t: u8) -> EntryKind {
        match t {
            ENTRY_DIARY => EntryKind::Diary,
            ENTRY_TODO => EntryKind::Todo,
            _ => EntryKind::Note,
        }
    }

    fn entry_type(self) -> u8 {
        match self {
            EntryKind::Note => ENTRY_NOTE,
            EntryKind::Diary => ENTRY_DIARY,
            EntryKind::Todo => ENTRY_TODO,
        }
    }
}

/// Shown in lists: only a short preview crosses into the JVM until an entry is opened.
#[derive(uniffi::Record)]
pub struct EntrySummary {
    pub entry_id: String,
    pub kind: EntryKind,
    pub title: String,
    /// Up to 4 non-blank lines of the body, at most 240 characters.
    pub preview: String,
    pub diary_date: Option<i32>,
    pub created_ms: i64,
    /// Time of the latest revision.
    pub updated_ms: i64,
    pub done: bool,
    pub has_conflict: bool,
    pub folder_id: Option<String>,
    /// 0 = none, 1..=8 = palette index.
    pub color: i32,
    pub pinned: bool,
    /// Set while the entry is in the trash.
    pub trashed_ms: Option<i64>,
    pub attachments: Vec<AttachmentInfo>,
    pub tags: Vec<String>,
}

/// A Markdown link `[text](note:<id>)` from one entry to another.
#[derive(uniffi::Record, Debug, PartialEq, Eq)]
pub struct NoteLink {
    pub from_id: String,
    pub to_id: String,
}

/// Ids of every `](note:<32 hex>)` in `text`, in order, without repeats.
pub(crate) fn note_targets(text: &str) -> Vec<Id> {
    let mut out = Vec::new();
    for (at, _) in text.match_indices("](note:") {
        let start = at + "](note:".len();
        if let Some(id) = text.get(start..start + 32).filter(|_| text[start + 32..].starts_with(')')).and_then(parse_id) {
            if !out.contains(&id) {
                out.push(id);
            }
        }
    }
    out
}

/// `[text](url)` becomes `text`: previews show what the reader sees, not link targets.
fn strip_links(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    while let Some(open) = rest.find('[') {
        let after = &rest[open + 1..];
        let Some(close) = after.find("](") else { break };
        let Some(end) = after[close + 2..].find(')') else { break };
        if after[..close].contains('\n') || after[close + 2..close + 2 + end].contains(char::is_whitespace) {
            out.push_str(&rest[..open + 1]);
            rest = after;
            continue;
        }
        out.push_str(&rest[..open]);
        out.push_str(&after[..close]);
        rest = &after[close + 2 + end + 1..];
    }
    out.push_str(rest);
    out
}

/// A line of the body for a list preview, or None for lines that show no text (code fences,
/// rules). Follows the renderer in the app's Markdown.kt: quotes and list markers go, task
/// boxes become ☐ / ☑, bullets become •. Headings and inline styles keep their markers: the
/// app draws them as bold and italic, like read mode.
fn preview_line(line: &str) -> Option<String> {
    let t = line.trim();
    if t.starts_with("```") {
        return None;
    }
    let mut marks = t.chars().filter(|c| !c.is_whitespace());
    if let Some(m @ ('-' | '*' | '_')) = marks.next() {
        if marks.clone().count() >= 2 && marks.all(|c| c == m) {
            return None;
        }
    }
    fn after_ws(s: &str) -> Option<&str> {
        s.strip_prefix(char::is_whitespace).map(str::trim_start)
    }
    if let Some(rest) = line.strip_prefix('>') {
        let rest = rest.strip_prefix(char::is_whitespace).unwrap_or(rest);
        return Some(strip_links(rest));
    }
    let body = line.trim_start();
    let indent = &line[..line.len() - body.len()];
    let line = match body.strip_prefix(['-', '*', '+']).and_then(after_ws) {
        Some(item) => {
            let task = ["[ ]", "[x]", "[X]"].iter().find_map(|b| {
                item.strip_prefix(b).filter(|r| r.is_empty() || r.starts_with(char::is_whitespace)).map(|r| (*b == "[ ]", r.trim_start()))
            });
            match task {
                Some((open, r)) => format!("{indent}{} {r}", if open { '☐' } else { '☑' }),
                None => format!("{indent}• {item}"),
            }
        }
        None => line.to_string(),
    };
    Some(strip_links(&line))
}

#[derive(uniffi::Record)]
pub struct TagCount {
    pub name: String,
    /// Entries carrying it, not counting the trash.
    pub count: u32,
}

/// One tag as stored: trimmed, without a leading '#', inner spaces as '-', at most 40 chars.
pub(crate) fn normalize_tag(t: &str) -> Option<String> {
    let t: String = t.trim().trim_start_matches('#').split_whitespace().collect::<Vec<_>>().join("-");
    let t: String = t.chars().take(40).collect();
    (!t.is_empty()).then_some(t)
}

/// Normalized, de-duplicated ignoring case, first spelling wins.
pub(crate) fn normalize_tags(tags: &[String]) -> Vec<String> {
    let mut seen = HashSet::new();
    tags.iter().filter_map(|t| normalize_tag(t)).filter(|t| seen.insert(fold(t))).collect()
}

#[derive(uniffi::Record)]
pub struct EntryDetail {
    pub entry_id: String,
    pub kind: EntryKind,
    pub title: String,
    pub body: String,
    pub diary_date: Option<i32>,
    pub created_ms: i64,
    pub updated_ms: i64,
    pub done: bool,
    pub folder_id: Option<String>,
    pub color: i32,
    pub pinned: bool,
    pub trashed_ms: Option<i64>,
    pub attachments: Vec<AttachmentInfo>,
    pub tags: Vec<String>,
    /// Body of a concurrent version, offered for merging (spec §7.1).
    pub conflict_body: Option<String>,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct AttachmentInfo {
    /// Hex seed; pass it to `attachment_data` / `thumbnail_data`.
    pub attachment_id: String,
    pub mime: String,
    pub size: i64,
    pub has_thumbnail: bool,
    /// Audio length; 0 for images.
    pub duration_ms: i64,
    pub width: i32,
    pub height: i32,
}

fn info(r: &AttachmentRef) -> AttachmentInfo {
    AttachmentInfo {
        attachment_id: hex(&r.seed),
        mime: r.mime.clone(),
        size: r.size as i64,
        has_thumbnail: r.thumb.is_some(),
        duration_ms: r.duration_ms as i64,
        width: r.width as i32,
        height: r.height as i32,
    }
}

fn infos(p: &PutOp) -> Vec<AttachmentInfo> {
    attachment::refs(&p.unknown).map(|r| info(&r)).collect()
}

#[derive(uniffi::Record)]
pub struct Folder {
    pub folder_id: String,
    pub name: String,
    /// Notes in it, not counting the trash.
    pub note_count: u32,
}

fn attr<'a>(p: &'a PutOp, tag: u8) -> Option<&'a [u8]> {
    p.unknown.iter().find(|f| f.tag == tag).map(|f| f.value.as_slice())
}

/// `fields` with `tag` replaced by `value`, or removed if None.
fn with_attr(fields: &[Field], tag: u8, value: Option<Vec<u8>>) -> Vec<Field> {
    let mut out: Vec<Field> = fields.iter().filter(|f| f.tag != tag).cloned().collect();
    if let Some(value) = value {
        out.push(Field { tag, value });
    }
    out
}

fn is_done(p: &PutOp) -> bool {
    attr(p, T_DONE) == Some(&[1])
}

fn folder_of(p: &PutOp) -> Option<Id> {
    attr(p, T_FOLDER).and_then(|b| b.try_into().ok())
}

fn trashed_ms(p: &PutOp) -> Option<u64> {
    attr(p, T_TRASHED).and_then(|b| b.try_into().ok()).map(u64::from_le_bytes)
}

fn color_of(p: &PutOp) -> i32 {
    match attr(p, T_COLOR) {
        Some([c]) if (1..=8).contains(c) => *c as i32,
        _ => 0,
    }
}

/// Case-, accent- and ё/е-insensitive form for search. Marks are stripped only from Latin
/// letters, so Cyrillic й stays distinct from и.
pub(crate) fn fold(s: &str) -> String {
    use unicode_normalization::UnicodeNormalization;
    let mut out = String::with_capacity(s.len());
    for c in s.chars().flat_map(char::to_lowercase) {
        if c == 'ё' {
            out.push('е');
        } else if (c as u32) < 0x0250 && !c.is_ascii() {
            out.extend(c.to_string().nfd().filter(|d| !('\u{0300}'..='\u{036F}').contains(d)));
        } else {
            out.push(c);
        }
    }
    out
}

#[derive(uniffi::Object)]
pub struct Session {
    pub(crate) shared: Arc<Shared>,
    pub(crate) inner: Mutex<Inner>,
}

pub(crate) struct Inner {
    /// None once closed.
    keys: Option<VaultKeys>,
    pub vault_id: Id,
    /// Heap index of this vault's KEYWRAP block.
    pub keywrap_index: u32,
    session_id: Id,
    ops: HashMap<Id, Op>,
    op_blocks: HashMap<Id, Vec<u32>>,
    /// Own PAD blocks, which "reclaim space" may remove (spec §8.3).
    pub pad_blocks: BTreeSet<u32>,
    state: State,
    last_hlc: u64,
    /// Ops written in this session: their blocks never existed in any earlier snapshot of
    /// the file, so purging them is invisible even in `manual` mode (spec §8.2).
    born: HashSet<Id>,
    /// Own BLOB blocks by nonce. Never decrypted on unlock; read on demand.
    blob_blocks: HashMap<Nonce, u32>,
    /// BLOB blocks written in this session (same reasoning as [Inner::born]).
    born_blocks: HashSet<u32>,
    /// Part nonces per BLOB seed, derived once.
    nonce_cache: HashMap<Id, Vec<Nonce>>,
}

/// When replaced revisions from earlier sessions are physically erased (spec §8.2).
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum PurgeMode {
    /// Erase on the spot. Someone with two copies of the file and the password of another
    /// vault can see an old unknown block change.
    Immediate,
    /// Erase only revisions born in this session; older ones wait for `purge_now`.
    Manual,
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

pub(crate) fn random_id() -> Id {
    crypto::random_bytes(16).try_into().expect("16 bytes")
}

/// First lines of the body for lists: without photos placed in the text (`![center m](att:…)`),
/// the alignment prefixes `:-: ` and `--: `, and the block markers [preview_line] removes.
fn preview(body: &str) -> String {
    let lines: Vec<String> = body
        .lines()
        .map(str::trim_end)
        .filter(|l| !(l.starts_with("![") && l.contains("](att:") && l.ends_with(')')))
        .map(|l| l.strip_prefix(":-: ").or_else(|| l.strip_prefix("--: ")).unwrap_or(l))
        .filter_map(preview_line)
        .filter(|l| !l.trim().is_empty())
        .take(4)
        .collect();
    lines.join("\n").chars().take(240).collect()
}

impl Inner {
    pub fn keys(&self) -> Result<&VaultKeys> {
        self.keys.as_ref().ok_or(CoreError::Locked)
    }

    pub fn load(heap: &Heap, cipher: &BlockCipher, vault_id: Id, keywrap_index: u32, v: Key, nonces: &[Nonce]) -> Result<Inner> {
        let keys = VaultKeys::derive(&v);
        drop(v);

        let mut meta = Vec::new();
        let mut pad_blocks = BTreeSet::new();
        let mut blob_blocks = HashMap::new();
        for (i, n) in nonces.iter().enumerate() {
            if locator::matches(&keys.meta_loc, n) {
                meta.push(i as u32);
            } else if locator::matches(&keys.pad_loc, n) {
                pad_blocks.insert(i as u32);
            } else if locator::matches(&keys.blob_loc, n) {
                blob_blocks.insert(*n, i as u32);
            }
        }

        let mut assembler = Assembler::default();
        heap.read_blocks(&meta, |i, block| {
            if let Some(frame) = cipher.open(&keys.enc, &block).and_then(|pt| Frame::decode(&pt)) {
                if frame.kind == Kind::Op {
                    assembler.add(frame, i);
                }
            }
        })?;

        let mut ops = HashMap::new();
        let mut op_blocks = HashMap::new();
        for obj in assembler.complete() {
            if let Some(op) = Op::decode(obj.object_id, &obj.data) {
                op_blocks.insert(op.op_id(), obj.blocks);
                ops.insert(op.op_id(), op);
            }
        }

        let last_hlc = ops.values().map(Op::hlc).max().unwrap_or(0);
        let mut inner = Inner {
            keys: Some(keys),
            vault_id,
            keywrap_index,
            session_id: random_id(),
            ops,
            op_blocks,
            pad_blocks,
            state: State::default(),
            last_hlc,
            born: HashSet::new(),
            blob_blocks,
            born_blocks: HashSet::new(),
            nonce_cache: HashMap::new(),
        };
        inner.recompute(heap, cipher)?;
        Ok(inner)
    }

    fn next_hlc(&mut self) -> u64 {
        self.last_hlc = (self.last_hlc + 1).max(now_ms() << 16);
        self.last_hlc
    }

    fn put_head(&self, entry_id: &Id) -> Option<&PutOp> {
        let head = self.state.entries.get(entry_id)?.head;
        match self.ops.get(&head)? {
            Op::Put(p) => Some(p),
            Op::Delete(_) => None,
        }
    }

    fn commit(&mut self, heap: &Heap, cipher: &BlockCipher, op: Op) -> Result<()> {
        self.commit_batch(heap, cipher, vec![op])
    }

    /// Several operations in one append, shuffled together with one portion of chaff.
    fn commit_batch(&mut self, heap: &Heap, cipher: &BlockCipher, ops: Vec<Op>) -> Result<()> {
        self.commit_with_blobs(heap, cipher, ops, Vec::new())
    }

    /// Like [Inner::commit_batch], plus ready-sealed BLOB blocks in the same append.
    fn commit_with_blobs(&mut self, heap: &Heap, cipher: &BlockCipher, ops: Vec<Op>, blobs: Vec<(Nonce, Vec<u8>)>) -> Result<()> {
        let keys = self.keys()?;
        let mut data = Vec::new();
        let mut spans = Vec::with_capacity(ops.len());
        for op in &ops {
            let encoded = Zeroizing::new(op.encode());
            let start = data.len();
            for f in Frame::split(Kind::Op, op.op_id(), &encoded) {
                data.push(cipher.seal(&keys.enc, &locator::nonce(&keys.meta_loc), &f.encode()));
            }
            spans.push(start..data.len());
        }
        let blob_start = data.len();
        let blob_nonces: Vec<Nonce> = blobs.iter().map(|(n, _)| *n).collect();
        data.extend(blobs.into_iter().map(|(_, b)| b));
        let written = data.len();
        let mut batch = data;
        batch.extend(chaff_blocks(cipher, chaff::on_commit(written)));
        let positions = append_shuffled(heap, batch)?;
        for (op, span) in ops.into_iter().zip(spans) {
            self.op_blocks.insert(op.op_id(), positions[span].to_vec());
            self.born.insert(op.op_id());
            self.ops.insert(op.op_id(), op);
        }
        for (k, nonce) in blob_nonces.into_iter().enumerate() {
            let at = positions[blob_start + k];
            self.blob_blocks.insert(nonce, at);
            self.born_blocks.insert(at);
        }
        self.recompute(heap, cipher)
    }

    fn part_nonces(&mut self, seed: &Id, parts: u32) -> Result<Vec<Nonce>> {
        if let Some(n) = self.nonce_cache.get(seed).filter(|n| n.len() == parts as usize) {
            return Ok(n.clone());
        }
        let keys = self.keys()?;
        let nonces: Vec<Nonce> = (0..parts).map(|i| attachment::part_nonce(&keys.blob_nonce, &keys.blob_loc, seed, i)).collect();
        self.nonce_cache.insert(*seed, nonces.clone());
        Ok(nonces)
    }

    /// Nonces of every BLOB part any op still in memory refers to: live heads, and in
    /// manual mode the replaced revisions that wait for purging.
    fn referenced_blob_nonces(&mut self) -> Result<HashSet<Nonce>> {
        self.blob_nonces_of(false)
    }

    /// With `live_only`, ignore replaced revisions: what stays after `purge_now`.
    fn blob_nonces_of(&mut self, live_only: bool) -> Result<HashSet<Nonce>> {
        let dead = &self.state.dead;
        let blobs: Vec<(Id, u32)> = self
            .ops
            .values()
            .filter(|o| !live_only || !dead.contains(&o.op_id()))
            .filter_map(|o| match o {
                Op::Put(p) => Some(attachment::refs(&p.unknown).flat_map(|r| r.blobs().collect::<Vec<_>>()).collect::<Vec<_>>()),
                Op::Delete(_) => None,
            })
            .flatten()
            .collect();
        let mut out = HashSet::new();
        for (seed, parts) in blobs {
            out.extend(self.part_nonces(&seed, parts)?);
        }
        Ok(out)
    }

    /// Seals `data` as the BLOB `seed` (spec §6.4), ready for [Inner::commit_with_blobs].
    fn seal_blob(&mut self, cipher: &BlockCipher, seed: Id, data: &[u8]) -> Result<Vec<(Nonce, Vec<u8>)>> {
        let frames = Frame::split(Kind::Blob, seed, data);
        let nonces = self.part_nonces(&seed, frames.len() as u32)?;
        let keys = self.keys()?;
        Ok(frames.iter().zip(nonces).map(|(f, n)| (n, cipher.seal(&keys.enc, &n, &f.encode()))).collect())
    }

    /// Adds a file (and optional thumbnail) to an existing entry, in one commit with the
    /// entry's new revision.
    #[allow(clippy::too_many_arguments)]
    pub fn add_attachment(
        &mut self,
        heap: &Heap,
        cipher: &BlockCipher,
        entry_id: Id,
        data: &[u8],
        mime: &str,
        thumbnail: Option<&[u8]>,
        duration_ms: u64,
        width: u32,
        height: u32,
    ) -> Result<AttachmentInfo> {
        self.keys()?;
        if data.is_empty() || data.len() > MAX_ATTACHMENT_BYTES || thumbnail.is_some_and(|t| t.len() > MAX_ATTACHMENT_BYTES) {
            return Err(CoreError::Invalid("attachment is empty or too large".into()));
        }
        let prev = self.put_head(&entry_id).cloned().ok_or_else(|| CoreError::Invalid("no such entry".into()))?;
        let seed = random_id();
        let mut blobs = self.seal_blob(cipher, seed, data)?;
        let thumb = match thumbnail {
            Some(t) => {
                let tseed = random_id();
                blobs.extend(self.seal_blob(cipher, tseed, t)?);
                Some((tseed, attachment::parts_for(t.len())))
            }
            None => None,
        };
        let r = AttachmentRef {
            seed,
            parts: attachment::parts_for(data.len()),
            size: data.len() as u64,
            mime: mime.to_owned(),
            thumb,
            duration_ms,
            width,
            height,
        };
        let mut unknown = prev.unknown.clone();
        unknown.push(r.field());
        let op = self.revision(entry_id, Some(&prev), prev.entry_type, prev.title.clone(), prev.body.clone(), unknown);
        self.commit_with_blobs(heap, cipher, vec![op], blobs)?;
        Ok(info(&r))
    }

    /// Puts the entry's attachments in the order of `seeds`; unlisted ones keep their place
    /// after the listed ones. Other fields are untouched.
    pub fn reorder_attachments(&mut self, heap: &Heap, cipher: &BlockCipher, entry_id: Id, seeds: &[Id]) -> Result<()> {
        self.keys()?;
        let prev = self.put_head(&entry_id).cloned().ok_or_else(|| CoreError::Invalid("no such entry".into()))?;
        let (mut atts, others): (Vec<Field>, Vec<Field>) = prev.unknown.iter().cloned().partition(|f| f.tag == T_ATTACHMENT);
        let rank = |f: &Field| {
            AttachmentRef::decode(&f.value).and_then(|r| seeds.iter().position(|s| *s == r.seed)).unwrap_or(usize::MAX)
        };
        let before: Vec<Vec<u8>> = atts.iter().map(|f| f.value.clone()).collect();
        atts.sort_by_key(rank); // stable: unlisted keep their relative order
        if atts.iter().map(|f| &f.value).eq(before.iter()) {
            return Ok(());
        }
        let unknown: Vec<Field> = others.into_iter().chain(atts).collect();
        let op = self.revision(entry_id, Some(&prev), prev.entry_type, prev.title.clone(), prev.body.clone(), unknown);
        self.commit(heap, cipher, op)
    }

    pub fn remove_attachment(&mut self, heap: &Heap, cipher: &BlockCipher, entry_id: Id, seed: Id) -> Result<()> {
        self.keys()?;
        let prev = self.put_head(&entry_id).cloned().ok_or_else(|| CoreError::Invalid("no such entry".into()))?;
        let unknown: Vec<Field> = prev
            .unknown
            .iter()
            .filter(|f| f.tag != T_ATTACHMENT || AttachmentRef::decode(&f.value).map(|r| r.seed) != Some(seed))
            .cloned()
            .collect();
        if unknown.len() == prev.unknown.len() {
            return Ok(());
        }
        let op = self.revision(entry_id, Some(&prev), prev.entry_type, prev.title.clone(), prev.body.clone(), unknown);
        self.commit(heap, cipher, op)
    }

    /// Decrypts the BLOB `seed` (a file or a thumbnail) of any entry of this vault.
    pub fn read_blob(&mut self, heap: &Heap, cipher: &BlockCipher, seed: &Id) -> Result<Zeroizing<Vec<u8>>> {
        let parts = self
            .ops
            .values()
            .filter_map(|o| match o {
                Op::Put(p) => Some(attachment::refs(&p.unknown).collect::<Vec<_>>()),
                Op::Delete(_) => None,
            })
            .flatten()
            .flat_map(|r| r.blobs().collect::<Vec<_>>())
            .find(|(s, _)| s == seed)
            .map(|(_, p)| p)
            .ok_or_else(|| CoreError::Invalid("no such attachment".into()))?;
        let nonces = self.part_nonces(seed, parts)?;
        let keys = self.keys()?;
        let mut out = Zeroizing::new(Vec::new());
        for (i, n) in nonces.iter().enumerate() {
            let at = *self.blob_blocks.get(n).ok_or_else(|| CoreError::Io("attachment part missing".into()))?;
            let block = heap.read_block(at)?;
            let frame = cipher
                .open(&keys.enc, &block)
                .and_then(|pt| Frame::decode(&pt))
                .filter(|f| f.kind == Kind::Blob && f.object_id == *seed && f.part_index == i as u32 && f.part_count == parts)
                .ok_or_else(|| CoreError::Io("attachment part corrupt".into()))?;
            out.extend_from_slice(&frame.data);
        }
        Ok(out)
    }

    fn recompute(&mut self, heap: &Heap, cipher: &BlockCipher) -> Result<()> {
        self.state = State::compute(&self.ops);
        self.purge_dead(heap, cipher, false).map(|_| ())
    }

    pub fn purge_mode(&self) -> PurgeMode {
        match self.setting(T_PURGE_MODE).map(|v| v.as_slice()) {
            Some([1]) => PurgeMode::Manual,
            _ => PurgeMode::Immediate,
        }
    }

    /// Dead revisions whose purge must wait in `manual` mode. Settings revisions are never
    /// deferred: a fired wipe rule must not survive in an old one.
    fn deferred(&self, id: &Id) -> bool {
        self.purge_mode() == PurgeMode::Manual
            && !self.born.contains(id)
            && self.ops.get(id).map(|o| o.entry_id() != SETTINGS_ID).unwrap_or(false)
    }

    /// Overwrites blocks of replaced revisions with own PAD (spec §8.2). Returns blocks erased.
    fn purge_dead(&mut self, heap: &Heap, cipher: &BlockCipher, everything: bool) -> Result<u32> {
        let dead: Vec<Id> = self.state.dead.iter().filter(|id| everything || !self.deferred(id)).copied().collect();
        let pad_loc = &self.keys.as_ref().ok_or(CoreError::Locked)?.pad_loc;
        let mut writes = Vec::new();
        for id in &dead {
            if let Some(blocks) = self.op_blocks.remove(id) {
                for b in blocks {
                    writes.push((b, cipher.pad(pad_loc)));
                    self.pad_blocks.insert(b);
                }
            }
            self.ops.remove(id);
        }
        // BLOBs nobody refers to any more: removed attachments, deleted or replaced entries.
        let manual = self.purge_mode() == PurgeMode::Manual;
        let referenced = self.referenced_blob_nonces()?;
        let orphans: Vec<(Nonce, u32)> = self
            .blob_blocks
            .iter()
            .filter(|(n, at)| !referenced.contains(*n) && (everything || !manual || self.born_blocks.contains(at)))
            .map(|(n, at)| (*n, *at))
            .collect();
        let pad_loc = &self.keys.as_ref().ok_or(CoreError::Locked)?.pad_loc;
        for (n, at) in orphans {
            self.blob_blocks.remove(&n);
            writes.push((at, cipher.pad(pad_loc)));
            self.pad_blocks.insert(at);
        }
        heap.overwrite(&writes)?;
        Ok(writes.len() as u32)
    }

    pub fn pending_purge_blocks(&mut self) -> u32 {
        let ops: u32 = self.state.dead.iter().filter_map(|id| self.op_blocks.get(id)).map(|b| b.len() as u32).sum();
        let referenced = self.blob_nonces_of(true).unwrap_or_default();
        let blobs = self.blob_blocks.keys().filter(|n| !referenced.contains(*n)).count() as u32;
        ops + blobs
    }

    pub fn purge_now(&mut self, heap: &Heap, cipher: &BlockCipher) -> Result<u32> {
        self.keys()?;
        self.purge_dead(heap, cipher, true)
    }

    pub fn set_purge_mode(&mut self, heap: &Heap, cipher: &BlockCipher, mode: PurgeMode) -> Result<()> {
        let value = match mode {
            PurgeMode::Immediate => None,
            PurgeMode::Manual => Some(vec![1]),
        };
        self.set_setting(heap, cipher, T_PURGE_MODE, value)
    }

    /// New revision of an entry. `kind` only applies to a new entry; an existing one keeps its own.
    /// New revision of an entry. `kind` only applies to a new entry; an existing one keeps its own.
    /// A new note can go straight into `folder`.
    #[allow(clippy::too_many_arguments)]
    pub fn save(
        &mut self,
        heap: &Heap,
        cipher: &BlockCipher,
        entry_id: Option<Id>,
        kind: EntryKind,
        folder: Option<Id>,
        title: &str,
        body: &str,
    ) -> Result<Id> {
        self.keys()?;
        let id = entry_id.unwrap_or_else(random_id);
        let prev = self.put_head(&id).cloned();
        let unknown = match &prev {
            Some(p) => p.unknown.clone(),
            None => with_attr(&[], T_FOLDER, folder.map(|f| f.to_vec())),
        };
        let op = self.revision(id, prev.as_ref(), kind.entry_type(), title.to_owned(), body.to_owned(), unknown);
        self.commit(heap, cipher, op)?;
        Ok(id)
    }

    /// Moves a diary entry to another day (days since 1970-01-01). Other kinds are left alone.
    pub fn set_diary_date(&mut self, heap: &Heap, cipher: &BlockCipher, entry_id: Id, day: u32) -> Result<()> {
        self.keys()?;
        let prev = self.put_head(&entry_id).cloned().ok_or_else(|| CoreError::Invalid("no such entry".into()))?;
        if prev.entry_type != ENTRY_DIARY || prev.diary_date == Some(day) {
            return Ok(());
        }
        let mut op = self.revision(entry_id, Some(&prev), prev.entry_type, prev.title.clone(), prev.body.clone(), prev.unknown.clone());
        if let Op::Put(q) = &mut op {
            q.diary_date = Some(day);
        }
        self.commit(heap, cipher, op)
    }

    pub fn set_done(&mut self, heap: &Heap, cipher: &BlockCipher, entry_id: Id, done: bool) -> Result<()> {
        self.set_attr_many(heap, cipher, &[entry_id], T_DONE, done.then(|| vec![1])).map(|_| ())
    }

    /// The next revision of `id`, not yet committed. Batch callers collect several.
    fn revision(&mut self, id: Id, prev: Option<&PutOp>, entry_type: u8, title: String, body: String, unknown: Vec<Field>) -> Op {
        let entry_type = prev.map(|p| p.entry_type).unwrap_or(entry_type);
        let diary_date = match prev {
            Some(p) => p.diary_date,
            None if entry_type == ENTRY_DIARY => Some((now_ms() / 86_400_000) as u32),
            None => None,
        };
        Op::Put(PutOp {
            op_id: random_id(),
            hlc: self.next_hlc(),
            session_id: self.session_id,
            entry_id: id,
            supersedes: self.state.supersedes_for(&id, &self.ops),
            entry_type,
            created_ms: prev.map(|p| p.created_ms).unwrap_or_else(now_ms),
            diary_date,
            title,
            body,
            tags: prev.map(|p| p.tags.clone()).unwrap_or_default(),
            unknown,
        })
    }

    fn delete_op(&mut self, id: Id) -> Op {
        Op::Delete(DeleteOp {
            op_id: random_id(),
            hlc: self.next_hlc(),
            session_id: self.session_id,
            entry_id: id,
            supersedes: self.state.supersedes_for(&id, &self.ops),
        })
    }

    /// Distinct, existing, user-visible entries among `ids`.
    fn targets(&self, ids: &[Id]) -> Vec<PutOp> {
        let mut seen = HashSet::new();
        ids.iter()
            .filter(|id| **id != SETTINGS_ID && seen.insert(**id))
            .filter_map(|id| self.put_head(id).cloned())
            .collect()
    }

    /// Sets (or removes, with None) one attribute on many entries in one commit.
    /// Entries that already have that value are left alone. Returns how many changed.
    pub fn set_attr_many(&mut self, heap: &Heap, cipher: &BlockCipher, ids: &[Id], tag: u8, value: Option<Vec<u8>>) -> Result<u32> {
        self.keys()?;
        let mut ops = Vec::new();
        for p in self.targets(ids) {
            if attr(&p, tag) == value.as_deref() {
                continue;
            }
            let unknown = with_attr(&p.unknown, tag, value.clone());
            ops.push(self.revision(p.entry_id, Some(&p), p.entry_type, p.title.clone(), p.body.clone(), unknown));
        }
        let n = ops.len() as u32;
        if n > 0 {
            self.commit_batch(heap, cipher, ops)?;
        }
        Ok(n)
    }

    /// Deletes every listed entry in one commit. Unknown ids and duplicates are skipped.
    pub fn delete_many(&mut self, heap: &Heap, cipher: &BlockCipher, ids: &[Id]) -> Result<u32> {
        self.keys()?;
        let ops: Vec<Op> = self.targets(ids).iter().map(|p| p.entry_id).collect::<Vec<_>>().into_iter().map(|id| self.delete_op(id)).collect();
        let n = ops.len() as u32;
        if n > 0 {
            self.commit_batch(heap, cipher, ops)?;
        }
        Ok(n)
    }

    pub fn create_folder(&mut self, heap: &Heap, cipher: &BlockCipher, name: &str) -> Result<Id> {
        self.keys()?;
        let id = random_id();
        let op = self.revision(id, None, ENTRY_FOLDER, name.to_owned(), String::new(), Vec::new());
        self.commit(heap, cipher, op)?;
        Ok(id)
    }

    pub fn rename_folder(&mut self, heap: &Heap, cipher: &BlockCipher, id: Id, name: &str) -> Result<()> {
        self.keys()?;
        let p = self.put_head(&id).filter(|p| p.entry_type == ENTRY_FOLDER).cloned();
        let p = p.ok_or_else(|| CoreError::Invalid("no such folder".into()))?;
        let op = self.revision(id, Some(&p), ENTRY_FOLDER, name.to_owned(), String::new(), p.unknown.clone());
        self.commit(heap, cipher, op)
    }

    /// Deletes the folder; its notes move to "no folder" in the same commit.
    pub fn delete_folder(&mut self, heap: &Heap, cipher: &BlockCipher, id: Id) -> Result<()> {
        self.keys()?;
        if self.put_head(&id).map(|p| p.entry_type) != Some(ENTRY_FOLDER) {
            return Ok(());
        }
        let members: Vec<PutOp> = self.heads().into_iter().filter(|p| folder_of(p) == Some(id)).cloned().collect();
        let mut ops: Vec<Op> = Vec::new();
        for p in members {
            let unknown = with_attr(&p.unknown, T_FOLDER, None);
            ops.push(self.revision(p.entry_id, Some(&p), p.entry_type, p.title.clone(), p.body.clone(), unknown));
        }
        ops.push(self.delete_op(id));
        self.commit_batch(heap, cipher, ops)
    }

    /// Deletes for good everything trashed more than [TRASH_RETENTION_MS] before `now`.
    pub fn purge_expired_trash(&mut self, heap: &Heap, cipher: &BlockCipher, now: u64) -> Result<u32> {
        let expired: Vec<Id> = self
            .heads()
            .into_iter()
            .filter(|p| trashed_ms(p).is_some_and(|t| now.saturating_sub(t) > TRASH_RETENTION_MS))
            .map(|p| p.entry_id)
            .collect();
        self.delete_many(heap, cipher, &expired)
    }

    pub fn trashed_ids(&self) -> Vec<Id> {
        self.heads().into_iter().filter(|p| trashed_ms(p).is_some()).map(|p| p.entry_id).collect()
    }

    pub fn delete(&mut self, heap: &Heap, cipher: &BlockCipher, entry_id: Id) -> Result<()> {
        self.keys()?;
        if !self.state.entries.contains_key(&entry_id) {
            return Ok(());
        }
        let op = DeleteOp {
            op_id: random_id(),
            hlc: self.next_hlc(),
            session_id: self.session_id,
            entry_id,
            supersedes: self.state.supersedes_for(&entry_id, &self.ops),
        };
        self.commit(heap, cipher, Op::Delete(op))
    }

    fn setting(&self, tag: u8) -> Option<&Vec<u8>> {
        self.put_head(&SETTINGS_ID)?.unknown.iter().find(|f| f.tag == tag).map(|f| &f.value)
    }

    pub fn wipe_target(&self) -> Option<Vec<Key>> {
        let raw = self.setting(T_WIPE_TARGET)?;
        if raw.len() != 4 * KEY_LEN {
            return None;
        }
        Some(raw.chunks(KEY_LEN).map(Key::from_bytes).collect())
    }

    pub fn set_wipe_target(&mut self, heap: &Heap, cipher: &BlockCipher, target: Option<[&Key; 4]>) -> Result<()> {
        let value = target.map(|keys| keys.iter().flat_map(|k| k.bytes().iter().copied()).collect());
        self.set_setting(heap, cipher, T_WIPE_TARGET, value)
    }

    /// New settings revision with `tag` set or removed; the old revision is always purged.
    fn set_setting(&mut self, heap: &Heap, cipher: &BlockCipher, tag: u8, value: Option<Vec<u8>>) -> Result<()> {
        self.keys()?;
        let prev = self.put_head(&SETTINGS_ID).cloned();
        if prev.as_ref().and_then(|p| p.unknown.iter().find(|f| f.tag == tag)).is_none() && value.is_none() {
            return Ok(());
        }
        let mut unknown: Vec<Field> = prev
            .as_ref()
            .map(|p| p.unknown.iter().filter(|f| f.tag != tag).cloned().collect())
            .unwrap_or_default();
        if let Some(value) = value {
            unknown.push(Field { tag, value });
        }
        let op = PutOp {
            op_id: random_id(),
            hlc: self.next_hlc(),
            session_id: self.session_id,
            entry_id: SETTINGS_ID,
            supersedes: self.state.supersedes_for(&SETTINGS_ID, &self.ops),
            entry_type: ENTRY_SETTINGS,
            created_ms: prev.as_ref().map(|p| p.created_ms).unwrap_or_else(now_ms),
            diary_date: None,
            title: String::new(),
            body: String::new(),
            tags: Vec::new(),
            unknown,
        };
        self.commit(heap, cipher, Op::Put(op))
    }

    /// Session-end chaff, then key wipe. Idempotent.
    pub fn close(&mut self, heap: &Heap, cipher: &BlockCipher) -> Result<()> {
        if self.keys.is_none() {
            return Ok(());
        }
        let n = chaff::on_session_end();
        let result = if n > 0 { append_shuffled(heap, chaff_blocks(cipher, n)).map(|_| ()) } else { Ok(()) };
        self.keys = None; // VaultKeys zeroize on drop
        self.ops.clear();
        self.op_blocks.clear();
        self.state = State::default();
        result
    }

    /// After heap compaction: move every remembered block index to its new place.
    pub fn remap(&mut self, mapping: &[Option<u32>]) {
        for blocks in self.op_blocks.values_mut() {
            *blocks = blocks.iter().filter_map(|&b| mapping[b as usize]).collect();
        }
        if let Some(Some(k)) = mapping.get(self.keywrap_index as usize) {
            self.keywrap_index = *k;
        }
        self.pad_blocks = self.pad_blocks.iter().filter_map(|&b| mapping[b as usize]).collect();
        self.blob_blocks = self.blob_blocks.iter().filter_map(|(n, &b)| mapping[b as usize].map(|m| (*n, m))).collect();
        self.born_blocks = self.born_blocks.iter().filter_map(|&b| mapping[b as usize]).collect();
    }

    /// Copies every entry and folder of `src` into this vault (spec §11.7). An entry is taken
    /// when it is new here or its latest revision in `src` is newer; it then replaces the
    /// local one whole. Only latest revisions travel: no history, no deletions. Attachments are
    /// re-encrypted under this vault's keys. Settings (purge mode, wipe rule) stay behind.
    /// Returns how many entries were taken.
    pub fn import_from(&mut self, heap: &Heap, cipher: &BlockCipher, src: &mut Inner, src_heap: &Heap, src_cipher: &BlockCipher) -> Result<u32> {
        self.keys()?;
        src.keys()?;
        let mut heads: Vec<PutOp> = src
            .state
            .entries
            .keys()
            .filter_map(|id| src.put_head(id).cloned())
            .filter(|p| p.entry_type != ENTRY_SETTINGS)
            .collect();
        // Folders first: an interrupted import never leaves notes in folders that are missing.
        heads.sort_by_key(|p| (p.entry_type != ENTRY_FOLDER, p.hlc));
        // Newest local operation per entry, deletions included: a later local delete holds.
        let mut local: HashMap<Id, u64> = HashMap::new();
        for o in self.ops.values() {
            let h = local.entry(o.entry_id()).or_default();
            *h = (*h).max(o.hlc());
        }
        let mut taken = 0;
        for p in heads {
            if local.get(&p.entry_id).is_some_and(|&h| h >= p.hlc) {
                continue;
            }
            let mut blobs = Vec::new();
            let mut body = p.body.clone();
            let mut unknown = Vec::with_capacity(p.unknown.len());
            for f in &p.unknown {
                if f.tag != T_ATTACHMENT {
                    unknown.push(f.clone());
                    continue;
                }
                let Some(mut r) = AttachmentRef::decode(&f.value) else { continue };
                let old = r.seed;
                r.seed = self.copy_blob(cipher, src, src_heap, src_cipher, &old, r.parts, &mut blobs)?;
                if let Some((tseed, parts)) = r.thumb {
                    r.thumb = Some((self.copy_blob(cipher, src, src_heap, src_cipher, &tseed, parts, &mut blobs)?, parts));
                }
                // Photos placed in the text refer to the attachment by id.
                body = body.replace(&format!("att:{}", hex(&old)), &format!("att:{}", hex(&r.seed)));
                unknown.push(r.field());
            }
            let prev = self.put_head(&p.entry_id).cloned();
            let mut op = self.revision(p.entry_id, prev.as_ref(), p.entry_type, p.title.clone(), body, unknown);
            if let Op::Put(q) = &mut op {
                // It supersedes every local revision, so it wins whatever its clock; keeping the
                // source's clock keeps the "edited" time.
                q.hlc = p.hlc;
                q.entry_type = p.entry_type;
                q.created_ms = p.created_ms;
                q.diary_date = p.diary_date;
                q.tags = p.tags.clone();
            }
            self.commit_with_blobs(heap, cipher, vec![op], blobs)?;
            taken += 1;
        }
        Ok(taken)
    }

    /// Re-encrypts the BLOB `seed` of `src` for this vault and returns its seed here. The new
    /// seed is keyed by this vault, so the same attachment copied again is not stored twice,
    /// and two vaults never share seeds (or locators).
    #[allow(clippy::too_many_arguments)]
    fn copy_blob(
        &mut self,
        cipher: &BlockCipher,
        src: &mut Inner,
        src_heap: &Heap,
        src_cipher: &BlockCipher,
        seed: &Id,
        parts: u32,
        out: &mut Vec<(Nonce, Vec<u8>)>,
    ) -> Result<Id> {
        let new_seed = crypto::keyed_hash16(&self.keys()?.blob_nonce, &[b"plmp1-import".as_slice(), seed].concat());
        let nonces = self.part_nonces(&new_seed, parts)?;
        let present = nonces.iter().all(|n| self.blob_blocks.contains_key(n) || out.iter().any(|(o, _)| o == n));
        if !present {
            let data = src.read_blob(src_heap, src_cipher, seed)?;
            out.extend(self.seal_blob(cipher, new_seed, &data)?);
        }
        Ok(new_seed)
    }
    fn heads(&self) -> Vec<&PutOp> {
        let mut list: Vec<&PutOp> = self
            .state
            .entries
            .keys()
            .filter_map(|id| self.put_head(id))
            .filter(|p| p.entry_type != ENTRY_SETTINGS && p.entry_type != ENTRY_FOLDER)
            .collect();
        list.sort_by(|a, b| (b.diary_date.unwrap_or(0), b.created_ms).cmp(&(a.diary_date.unwrap_or(0), a.created_ms)));
        list
    }

    fn summary(&self, p: &PutOp) -> EntrySummary {
        EntrySummary {
            entry_id: hex(&p.entry_id),
            kind: EntryKind::from_type(p.entry_type),
            title: p.title.clone(),
            preview: preview(&p.body),
            diary_date: p.diary_date.map(|d| d as i32),
            created_ms: p.created_ms as i64,
            updated_ms: (p.hlc >> 16) as i64,
            done: is_done(p),
            has_conflict: !self.state.entries[&p.entry_id].others.is_empty(),
            folder_id: folder_of(p).map(|f| hex(&f)),
            color: color_of(p),
            pinned: attr(p, T_PINNED) == Some(&[1]),
            trashed_ms: trashed_ms(p).map(|t| t as i64),
            attachments: infos(p),
            tags: p.tags.clone(),
        }
    }

    /// Links between live entries (not in the trash, target existing), for backlinks and the graph.
    fn note_links(&self) -> Vec<NoteLink> {
        let live: Vec<&PutOp> = self.heads().into_iter().filter(|p| trashed_ms(p).is_none()).collect();
        let ids: HashSet<Id> = live.iter().map(|p| p.entry_id).collect();
        let mut out = Vec::new();
        for p in &live {
            for to in note_targets(&p.body) {
                if to != p.entry_id && ids.contains(&to) {
                    out.push(NoteLink { from_id: hex(&p.entry_id), to_id: hex(&to) });
                }
            }
        }
        out
    }

    fn all_tags(&self) -> Vec<TagCount> {
        let mut counts: HashMap<String, (String, u32)> = HashMap::new();
        for p in self.heads().into_iter().filter(|p| trashed_ms(p).is_none()) {
            for t in &p.tags {
                counts.entry(fold(t)).or_insert_with(|| (t.clone(), 0)).1 += 1;
            }
        }
        let mut out: Vec<TagCount> = counts.into_values().map(|(name, count)| TagCount { name, count }).collect();
        out.sort_by(|a, b| b.count.cmp(&a.count).then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())));
        out
    }

    /// New revisions with `change` applied to each entry's tags; one commit.
    fn retag(&mut self, heap: &Heap, cipher: &BlockCipher, ids: &[Id], change: impl Fn(&[String]) -> Vec<String>) -> Result<u32> {
        self.retag_with(heap, cipher, ids, true, change)
    }

    /// With `respell`, a tag that already exists in the vault keeps its existing spelling;
    /// without, the spelling given wins (renaming "work" to "Work").
    fn retag_with(
        &mut self,
        heap: &Heap,
        cipher: &BlockCipher,
        ids: &[Id],
        respell: bool,
        change: impl Fn(&[String]) -> Vec<String>,
    ) -> Result<u32> {
        self.keys()?;
        let mut spelling: HashMap<String, String> = HashMap::new();
        if respell {
            for p in self.heads() {
                for t in &p.tags {
                    spelling.entry(fold(t)).or_insert_with(|| t.clone());
                }
            }
        }
        let mut ops = Vec::new();
        for p in self.targets(ids) {
            let changed = change(&p.tags);
            let changed: Vec<String> = changed
                .iter()
                .map(|t| normalize_tag(t).map(|n| spelling.get(&fold(&n)).cloned().unwrap_or(n)).unwrap_or_default())
                .collect();
            let tags = normalize_tags(&changed);
            if tags == p.tags {
                continue;
            }
            let mut op = self.revision(p.entry_id, Some(&p), p.entry_type, p.title.clone(), p.body.clone(), p.unknown.clone());
            if let Op::Put(put) = &mut op {
                put.tags = tags;
            }
            ops.push(op);
        }
        let n = ops.len() as u32;
        if n > 0 {
            self.commit_batch(heap, cipher, ops)?;
        }
        Ok(n)
    }

    /// Every entry (trash included) carrying a tag that folds to `tag`.
    fn tagged(&self, tag: &str) -> Vec<Id> {
        let f = fold(tag);
        self.heads().iter().filter(|p| p.tags.iter().any(|t| fold(t) == f)).map(|p| p.entry_id).collect()
    }

    /// Renames a tag in every entry; renaming onto an existing tag merges the two.
    pub fn rename_tag(&mut self, heap: &Heap, cipher: &BlockCipher, old: &str, new: &str) -> Result<u32> {
        let new = normalize_tag(new).ok_or_else(|| CoreError::Invalid("empty tag".into()))?;
        let old_f = fold(old);
        let ids = self.tagged(old);
        let respell = fold(&new) != old_f;
        self.retag_with(heap, cipher, &ids, respell, |tags| {
            tags.iter().map(|t| if fold(t) == old_f { new.clone() } else { t.clone() }).collect()
        })
    }

    pub fn delete_tag(&mut self, heap: &Heap, cipher: &BlockCipher, tag: &str) -> Result<u32> {
        let f = fold(tag);
        let ids = self.tagged(tag);
        self.retag(heap, cipher, &ids, |tags| tags.iter().filter(|t| fold(t) != f).cloned().collect())
    }

    /// Puts the folders in the order of `ids` (one commit; unchanged ones are left alone).
    pub fn reorder_folders(&mut self, heap: &Heap, cipher: &BlockCipher, ids: &[Id]) -> Result<()> {
        self.keys()?;
        let mut ops = Vec::new();
        for (i, id) in ids.iter().enumerate() {
            let Some(p) = self.put_head(id).cloned().filter(|p| p.entry_type == ENTRY_FOLDER) else { continue };
            let value = (i as u32).to_le_bytes().to_vec();
            if attr(&p, T_ORDER) == Some(value.as_slice()) {
                continue;
            }
            let unknown = with_attr(&p.unknown, T_ORDER, Some(value));
            ops.push(self.revision(p.entry_id, Some(&p), p.entry_type, p.title.clone(), p.body.clone(), unknown));
        }
        if !ops.is_empty() {
            self.commit_batch(heap, cipher, ops)?;
        }
        Ok(())
    }

    fn folders(&self) -> Vec<Folder> {
        let heads = self.heads();
        let mut folders: Vec<(u32, Folder)> = self
            .state
            .entries
            .keys()
            .filter_map(|id| self.put_head(id))
            .filter(|p| p.entry_type == ENTRY_FOLDER)
            .map(|f| {
                let order = attr(f, T_ORDER).and_then(|b| b.try_into().ok()).map(u32::from_le_bytes).unwrap_or(u32::MAX);
                let folder = Folder {
                    folder_id: hex(&f.entry_id),
                    name: f.title.clone(),
                    note_count: heads.iter().filter(|p| folder_of(p) == Some(f.entry_id) && trashed_ms(p).is_none()).count() as u32,
                };
                (order, folder)
            })
            .collect();
        folders.sort_by(|(a, x), (b, y)| a.cmp(b).then_with(|| x.name.to_lowercase().cmp(&y.name.to_lowercase())));
        folders.into_iter().map(|(_, f)| f).collect()
    }

    fn summaries(&self) -> Vec<EntrySummary> {
        self.heads().into_iter().map(|p| self.summary(p)).collect()
    }

    /// Entries whose title or body contains every word of `query` (see [fold]); a word
    /// starting with '#' must be a tag of the entry instead.
    fn search(&self, query: &str) -> Vec<EntrySummary> {
        let words: Vec<String> = fold(query).split_whitespace().map(str::to_owned).collect();
        if words.is_empty() {
            return self.summaries();
        }
        let (tag_words, text_words): (Vec<&String>, Vec<&String>) = words.iter().partition(|w| w.starts_with('#') && w.len() > 1);
        self.heads()
            .into_iter()
            .filter(|p| {
                let tags: Vec<String> = p.tags.iter().map(|t| fold(t)).collect();
                if !tag_words.iter().all(|w| tags.iter().any(|t| *t == w[1..])) {
                    return false;
                }
                let text = Zeroizing::new(fold(&format!("{}\n{}", p.title, p.body)));
                text_words.iter().all(|w| text.contains(w.as_str()))
            })
            .map(|p| self.summary(p))
            .collect()
    }

    fn detail(&self, id: &Id) -> Option<EntryDetail> {
        let p = self.put_head(id)?;
        if p.entry_type == ENTRY_SETTINGS || p.entry_type == ENTRY_FOLDER {
            return None;
        }
        let conflict_body = self.state.entries[id].others.first().and_then(|o| match self.ops.get(o) {
            Some(Op::Put(other)) => Some(other.body.clone()),
            _ => None,
        });
        Some(EntryDetail {
            entry_id: hex(id),
            kind: EntryKind::from_type(p.entry_type),
            title: p.title.clone(),
            body: p.body.clone(),
            diary_date: p.diary_date.map(|d| d as i32),
            created_ms: p.created_ms as i64,
            updated_ms: (p.hlc >> 16) as i64,
            done: is_done(p),
            folder_id: folder_of(p).map(|f| hex(&f)),
            color: color_of(p),
            pinned: attr(p, T_PINNED) == Some(&[1]),
            trashed_ms: trashed_ms(p).map(|t| t as i64),
            attachments: infos(p),
            tags: p.tags.clone(),
            conflict_body,
        })
    }
}

fn id_arg(s: &str) -> Result<Id> {
    parse_id(s).ok_or_else(|| CoreError::Invalid(format!("bad entry id {s:?}")))
}

impl Session {
    fn set_attr(&self, entry_ids: Vec<String>, tag: u8, value: Option<Vec<u8>>) -> Result<u32> {
        let ids = entry_ids.iter().map(|s| id_arg(s)).collect::<Result<Vec<_>>>()?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.set_attr_many(&heap, &self.shared.cipher, &ids, tag, value)
    }
}

#[uniffi::export]
impl Session {
    pub fn entries(&self) -> Result<Vec<EntrySummary>> {
        let inner = guard(&self.inner)?;
        inner.keys()?;
        Ok(inner.summaries())
    }

    pub fn entry(&self, entry_id: String) -> Result<Option<EntryDetail>> {
        let inner = guard(&self.inner)?;
        inner.keys()?;
        Ok(inner.detail(&id_arg(&entry_id)?))
    }

    /// Search over titles and bodies inside the core: case-, accent- and ё/е-insensitive,
    /// every word must match. An empty query returns everything.
    pub fn search(&self, query: String) -> Result<Vec<EntrySummary>> {
        let query = Zeroizing::new(query);
        let inner = guard(&self.inner)?;
        inner.keys()?;
        Ok(inner.search(&query))
    }

    /// Writes a new revision; `entry_id` None creates an entry of `kind`, in `folder_id` if
    /// given. Returns the entry id.
    pub fn save(&self, entry_id: Option<String>, kind: EntryKind, folder_id: Option<String>, title: String, body: String) -> Result<String> {
        let id = entry_id.as_deref().map(id_arg).transpose()?;
        let folder = folder_id.as_deref().map(id_arg).transpose()?;
        let heap = guard(&self.shared.heap)?;
        let mut inner = guard(&self.inner)?;
        let title = Zeroizing::new(title);
        let body = Zeroizing::new(body);
        let saved = inner.save(&heap, &self.shared.cipher, id, kind, folder, &title, &body)?;
        Ok(hex(&saved))
    }

    /// Replaces the entry's tags (normalized: no '#', spaces as '-', duplicates dropped).
    pub fn set_tags(&self, entry_id: String, tags: Vec<String>) -> Result<()> {
        let id = id_arg(&entry_id)?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.retag(&heap, &self.shared.cipher, &[id], |_| tags.clone()).map(|_| ())
    }

    /// Adds one tag to many entries in one commit. Returns how many changed.
    pub fn add_tag(&self, entry_ids: Vec<String>, tag: String) -> Result<u32> {
        let ids = entry_ids.iter().map(|s| id_arg(s)).collect::<Result<Vec<_>>>()?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.retag(&heap, &self.shared.cipher, &ids, |old| {
            let mut t = old.to_vec();
            t.push(tag.clone());
            t
        })
    }

    /// Every tag in use, most used first.
    pub fn note_links(&self) -> Result<Vec<NoteLink>> {
        let inner = guard(&self.inner)?;
        inner.keys()?;
        Ok(inner.note_links())
    }

    pub fn tags(&self) -> Result<Vec<TagCount>> {
        let inner = guard(&self.inner)?;
        inner.keys()?;
        Ok(inner.all_tags())
    }

    pub fn rename_tag(&self, old: String, new: String) -> Result<u32> {
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.rename_tag(&heap, &self.shared.cipher, &old, &new)
    }

    pub fn delete_tag(&self, tag: String) -> Result<u32> {
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.delete_tag(&heap, &self.shared.cipher, &tag)
    }

    pub fn reorder_folders(&self, folder_ids: Vec<String>) -> Result<()> {
        let ids = folder_ids.iter().map(|s| id_arg(s)).collect::<Result<Vec<_>>>()?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.reorder_folders(&heap, &self.shared.cipher, &ids)
    }

    pub fn folders(&self) -> Result<Vec<Folder>> {
        let inner = guard(&self.inner)?;
        inner.keys()?;
        Ok(inner.folders())
    }

    pub fn create_folder(&self, name: String) -> Result<String> {
        let heap = guard(&self.shared.heap)?;
        Ok(hex(&guard(&self.inner)?.create_folder(&heap, &self.shared.cipher, &name)?))
    }

    pub fn rename_folder(&self, folder_id: String, name: String) -> Result<()> {
        let id = id_arg(&folder_id)?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.rename_folder(&heap, &self.shared.cipher, id, &name)
    }

    /// Deletes the folder; its notes stay, without a folder.
    pub fn delete_folder(&self, folder_id: String) -> Result<()> {
        let id = id_arg(&folder_id)?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.delete_folder(&heap, &self.shared.cipher, id)
    }

    /// Moves entries into `folder_id`, or out of any folder with None.
    pub fn move_to_folder(&self, entry_ids: Vec<String>, folder_id: Option<String>) -> Result<u32> {
        let folder = folder_id.as_deref().map(id_arg).transpose()?;
        self.set_attr(entry_ids, T_FOLDER, folder.map(|f| f.to_vec()))
    }

    pub fn set_pinned(&self, entry_ids: Vec<String>, pinned: bool) -> Result<u32> {
        self.set_attr(entry_ids, T_PINNED, pinned.then(|| vec![1]))
    }

    /// `color` 0 removes the colour, 1..=8 picks from the palette.
    pub fn set_color(&self, entry_ids: Vec<String>, color: i32) -> Result<u32> {
        let value = match color {
            0 => None,
            1..=8 => Some(vec![color as u8]),
            _ => return Err(CoreError::Invalid(format!("colour {color}"))),
        };
        self.set_attr(entry_ids, T_COLOR, value)
    }

    /// Moves entries to the trash; they are deleted for good after 30 days.
    pub fn trash(&self, entry_ids: Vec<String>) -> Result<u32> {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
        self.set_attr(entry_ids, T_TRASHED, Some(now.to_le_bytes().to_vec()))
    }

    pub fn restore(&self, entry_ids: Vec<String>) -> Result<u32> {
        self.set_attr(entry_ids, T_TRASHED, None)
    }

    /// Deletes everything in the trash for good. Returns how many.
    pub fn empty_trash(&self) -> Result<u32> {
        let heap = guard(&self.shared.heap)?;
        let mut inner = guard(&self.inner)?;
        let ids = inner.trashed_ids();
        inner.delete_many(&heap, &self.shared.cipher, &ids)
    }

    /// Attaches a file to an existing entry. `thumbnail` is a small preview for images.
    #[allow(clippy::too_many_arguments)]
    pub fn add_attachment(
        &self,
        entry_id: String,
        data: Vec<u8>,
        mime: String,
        thumbnail: Option<Vec<u8>>,
        duration_ms: i64,
        width: i32,
        height: i32,
    ) -> Result<AttachmentInfo> {
        let id = id_arg(&entry_id)?;
        let data = Zeroizing::new(data);
        let thumbnail = thumbnail.map(Zeroizing::new);
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.add_attachment(
            &heap,
            &self.shared.cipher,
            id,
            &data,
            &mime,
            thumbnail.as_deref().map(|t| t.as_slice()),
            duration_ms.max(0) as u64,
            width.max(0) as u32,
            height.max(0) as u32,
        )
    }

    /// New order of the entry's attachments, by attachment id.
    pub fn reorder_attachments(&self, entry_id: String, attachment_ids: Vec<String>) -> Result<()> {
        let id = id_arg(&entry_id)?;
        let seeds = attachment_ids.iter().map(|s| id_arg(s)).collect::<Result<Vec<_>>>()?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.reorder_attachments(&heap, &self.shared.cipher, id, &seeds)
    }

    pub fn remove_attachment(&self, entry_id: String, attachment_id: String) -> Result<()> {
        let id = id_arg(&entry_id)?;
        let seed = id_arg(&attachment_id)?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.remove_attachment(&heap, &self.shared.cipher, id, seed)
    }

    pub fn attachment_data(&self, attachment_id: String) -> Result<Vec<u8>> {
        let seed = id_arg(&attachment_id)?;
        let heap = guard(&self.shared.heap)?;
        let data = guard(&self.inner)?.read_blob(&heap, &self.shared.cipher, &seed)?;
        Ok(data.to_vec())
    }

    /// The preview image of an attachment, if it has one.
    pub fn thumbnail_data(&self, attachment_id: String) -> Result<Option<Vec<u8>>> {
        let seed = id_arg(&attachment_id)?;
        let heap = guard(&self.shared.heap)?;
        let mut inner = guard(&self.inner)?;
        let thumb = inner
            .ops
            .values()
            .filter_map(|o| match o {
                Op::Put(p) => Some(attachment::refs(&p.unknown).collect::<Vec<_>>()),
                Op::Delete(_) => None,
            })
            .flatten()
            .find(|r| r.seed == seed)
            .and_then(|r| r.thumb);
        match thumb {
            Some((tseed, _)) => Ok(Some(inner.read_blob(&heap, &self.shared.cipher, &tseed)?.to_vec())),
            None => Ok(None),
        }
    }

    /// Deletes several entries at once. Returns how many were deleted.
    pub fn delete_many(&self, entry_ids: Vec<String>) -> Result<u32> {
        let ids = entry_ids.iter().map(|s| id_arg(s)).collect::<Result<Vec<_>>>()?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.delete_many(&heap, &self.shared.cipher, &ids)
    }

    /// Ticks or unticks a to-do item.
    pub fn set_diary_date(&self, entry_id: String, day: i32) -> Result<()> {
        let id = id_arg(&entry_id)?;
        let day = u32::try_from(day).map_err(|_| CoreError::Invalid("day before 1970".into()))?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.set_diary_date(&heap, &self.shared.cipher, id, day)
    }

    pub fn set_done(&self, entry_id: String, done: bool) -> Result<()> {
        let id = id_arg(&entry_id)?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.set_done(&heap, &self.shared.cipher, id, done)
    }

    pub fn delete(&self, entry_id: String) -> Result<()> {
        let id = id_arg(&entry_id)?;
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.delete(&heap, &self.shared.cipher, id)
    }

    /// True if the next unlock of this vault will shred another one.
    pub fn wipe_armed(&self) -> Result<bool> {
        Ok(guard(&self.inner)?.wipe_target().is_some())
    }

    pub fn purge_mode(&self) -> Result<PurgeMode> {
        Ok(guard(&self.inner)?.purge_mode())
    }

    pub fn set_purge_mode(&self, mode: PurgeMode) -> Result<()> {
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.set_purge_mode(&heap, &self.shared.cipher, mode)
    }

    /// Blocks of replaced revisions still waiting to be erased (manual mode).
    pub fn pending_purge_blocks(&self) -> Result<u32> {
        Ok(guard(&self.inner)?.pending_purge_blocks())
    }

    /// Erases every replaced revision now. Returns the blocks erased.
    pub fn purge_now(&self) -> Result<u32> {
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.purge_now(&heap, &self.shared.cipher)
    }

    /// Own PAD blocks that "reclaim space" would remove.
    pub fn reclaimable_blocks(&self) -> Result<u32> {
        Ok(guard(&self.inner)?.pad_blocks.len() as u32)
    }

    /// Session-end chaff, then key wipe. Named lock, not close: Kotlin's AutoCloseable.close
    /// already means "free the native object".
    pub fn lock(&self) -> Result<()> {
        let heap = guard(&self.shared.heap)?;
        guard(&self.inner)?.close(&heap, &self.shared.cipher)
    }

    pub fn is_locked(&self) -> bool {
        guard(&self.inner).map(|i| i.keys.is_none()).unwrap_or(true)
    }
}
