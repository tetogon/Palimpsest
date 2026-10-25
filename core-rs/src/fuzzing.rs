//! Entry points for cargo-fuzz (core-rs/fuzz). Built only with the `fuzzing` feature, never
//! into the app. Each one takes arbitrary bytes and must not panic, hang or allocate wildly.
//!
//! The parsers run on decrypted data, but that data is not always trusted: an exported vault
//! comes with its password, so whoever made the file chose every plaintext in it, and anyone
//! with a decoy password can write such blocks into a container. [vault] starts after the
//! key derivation (Argon2id would leave a few runs per second) and seals the fuzzer's bytes
//! into real blocks of a vault, then loads it, reads everything and imports it into another.

use crate::attachment::{self, AttachmentRef};
use crate::container::Shared;
use crate::crypto::Key;
use crate::format::*;
use crate::frame::{Frame, Kind};
use crate::heap::Heap;
use crate::keys::{locator, BlockCipher, VaultKeys};
use crate::op::Op;
use crate::session::{Inner, Session};
use crate::tlv;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

/// TLV is canonical: whatever parses writes back to the same bytes.
pub fn tlv(data: &[u8]) {
    let Some(fields) = tlv::parse(data, 0) else { return };
    let mut w = tlv::Writer::default();
    for f in &fields {
        w.put(f.tag, &f.value);
    }
    assert_eq!(w.into_bytes(), data, "TLV that parses must write back unchanged");
}

/// The first 16 bytes are the object id. A decoded operation encodes to bytes that decode
/// to an operation encoding the same way: rewriting (merges, imports) never drifts.
pub fn op(data: &[u8]) {
    if data.len() < 16 {
        return;
    }
    let id: Id = data[..16].try_into().expect("16 bytes");
    let Some(op) = Op::decode(id, &data[16..]) else { return };
    let once = op.encode();
    let again = Op::decode(id, &once).expect("an encoded operation decodes");
    assert_eq!(again.encode(), once, "operation encoding is stable");
}

/// Any block plaintext: a frame that decodes encodes back to the same plaintext.
pub fn frame(data: &[u8]) {
    let pt = fit(data);
    if let Some(f) = Frame::decode(&pt) {
        assert_eq!(&f.encode()[..], &pt[..], "frame round trip");
    }
}

pub fn attachment(data: &[u8]) {
    if let Some(a) = AttachmentRef::decode(data) {
        assert_eq!(AttachmentRef::decode(&a.encode()), Some(a), "attachment reference round trip");
    }
}

/// `data` is a list of records `mode u8 ‖ len u16 LE ‖ payload`, each sealed into the vault:
/// 0 an OP object split into frames, 1 a raw OP-block plaintext, 2 a BLOB block (seed = first
/// 16 bytes, part = next 4, plaintext = the rest), 3 a PAD block.
pub fn vault(data: &[u8]) {
    let _ = run_vault(data);
}

/// [vault], returning how many entries the loaded vault lists.
fn run_vault(data: &[u8]) -> Option<usize> {
    let src = Scratch::new("src");
    let dst = Scratch::new("dst");
    let src_key = Key::from_bytes(&[7; KEY_LEN]);
    let keys = VaultKeys::derive(&src_key);
    let (src_heap, src_cipher) = src.heap(0x11);

    // Block 0 stands in for the KEYWRAP: the vault is loaded by key, not by password.
    let mut blocks = vec![src_cipher.chaff()];
    let mut rest = data;
    while rest.len() >= 3 && blocks.len() < 600 {
        let mode = rest[0];
        let len = (u16::from_le_bytes([rest[1], rest[2]]) as usize).min(rest.len() - 3);
        let payload = &rest[3..3 + len];
        rest = &rest[3 + len..];
        let n = blocks.len() as u32;
        match mode % 4 {
            0 => {
                let mut id = [0xA5u8; 16];
                id[..4].copy_from_slice(&n.to_le_bytes());
                for f in Frame::split(Kind::Op, id, payload) {
                    let r = counter(blocks.len() as u32);
                    blocks.push(src_cipher.seal(&keys.enc, &nonce(&keys.meta_loc, &r), &f.encode()));
                }
            }
            1 => blocks.push(src_cipher.seal(&keys.enc, &nonce(&keys.meta_loc, &counter(n)), &fit(payload))),
            2 => {
                let mut seed = [0u8; 16];
                let s = payload.len().min(16);
                seed[..s].copy_from_slice(&payload[..s]);
                let part = payload.get(16..20).map_or(0, |b| u32::from_le_bytes(b.try_into().expect("4 bytes")) % 4);
                let at = attachment::part_nonce(&keys.blob_nonce, &keys.blob_loc, &seed, part);
                blocks.push(src_cipher.seal(&keys.enc, &at, &fit(payload.get(20..).unwrap_or(&[]))));
            }
            _ => {
                let mut b = nonce(&keys.pad_loc, &counter(n)).to_vec();
                b.extend(fit(payload).iter().chain(&[0u8; TAG_LEN]));
                blocks.push(b);
            }
        }
    }
    src_heap.append(&blocks).ok()?;
    let nonces = src_heap.read_nonces().ok()?;
    let mut src_inner = Inner::load(&src_heap, &src_cipher, [1; 16], 0, src_key, &nonces).ok()?;

    // The import path: everything the file holds goes into another, empty vault.
    let (dst_heap, dst_cipher) = dst.heap(0x22);
    dst_heap.append(&[dst_cipher.chaff()]).ok()?;
    let dst_nonces = dst_heap.read_nonces().ok()?;
    let mut dst_inner = Inner::load(&dst_heap, &dst_cipher, [2; 16], 0, Key::from_bytes(&[9; KEY_LEN]), &dst_nonces).ok()?;
    let _ = dst_inner.import_from(&dst_heap, &dst_cipher, &mut src_inner, &src_heap, &src_cipher);
    read_everything(&session(dst_heap, dst_cipher, dst_inner));

    let source = session(src_heap, src_cipher, src_inner);
    let listed = read_everything(&source);
    let _ = source.purge_now();
    let _ = source.empty_trash();
    let _ = source.lock();
    Some(listed)
}

fn session(heap: Heap, cipher: BlockCipher, inner: Inner) -> Session {
    let salt = heap.read_salt().unwrap_or_default();
    Session { shared: Arc::new(Shared { heap: Mutex::new(heap), cipher, salt }), inner: Mutex::new(inner) }
}

/// Every read the app does; returns how many entries are listed.
fn read_everything(s: &Session) -> usize {
    let Ok(entries) = s.entries() else { return 0 };
    for e in &entries {
        if let Ok(Some(d)) = s.entry(e.entry_id.clone()) {
            for a in &d.attachments {
                let _ = s.attachment_data(a.attachment_id.clone());
                let _ = s.thumbnail_data(a.attachment_id.clone());
            }
        }
    }
    let _ = s.search(String::new());
    let _ = s.search("a #b ё".into());
    let _ = s.note_links();
    let _ = s.tags();
    let _ = s.folders();
    let _ = s.wipe_armed();
    let _ = s.purge_mode();
    let _ = s.pending_purge_blocks();
    let _ = s.reclaimable_blocks();
    entries.len()
}

fn fit(data: &[u8]) -> Vec<u8> {
    let mut pt = data[..data.len().min(PT_LEN)].to_vec();
    pt.resize(PT_LEN, 0);
    pt
}

/// A fixed `r` per block, so a crash replays the same container.
fn counter(n: u32) -> [u8; R_LEN] {
    let mut r = [0x5Cu8; R_LEN];
    r[..4].copy_from_slice(&n.to_le_bytes());
    r
}

fn nonce(key: &Key, r: &[u8; R_LEN]) -> Nonce {
    let mut n = [0u8; NONCE_LEN];
    n[..R_LEN].copy_from_slice(r);
    n[R_LEN..].copy_from_slice(&locator::of(key, r));
    n
}

/// A container file in the temp folder for one run, removed afterwards.
struct Scratch(PathBuf);

impl Scratch {
    fn new(name: &str) -> Scratch {
        static RUN: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let run = RUN.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        Scratch(std::env::temp_dir().join(format!("plmp-fuzz-{}-{run}-{name}", std::process::id())))
    }

    fn heap(&self, salt: u8) -> (Heap, BlockCipher) {
        let heap = Heap::new(&self.0);
        let mut salt_block = vec![0u8; BLOCK_SIZE];
        salt_block[..SALT_LEN].fill(salt);
        heap.create(&salt_block).expect("temp folder is writable");
        (heap, BlockCipher::new(&salt_block[..SALT_LEN]))
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        for suffix in ["", ".nonces", ".tmp"] {
            let mut p = self.0.clone().into_os_string();
            p.push(suffix);
            let _ = std::fs::remove_file(p);
        }
    }
}

/// Inputs that reach deep on the first run: a real entry with a tag, a folder, an attachment
/// with its blob part and a deletion, in [vault]'s record format, plus one per simple target.
pub fn seeds() -> Vec<(&'static str, &'static str, Vec<u8>)> {
    use crate::op::{DeleteOp, PutOp};
    let put = |entry: u8, ty: u8, body: &str, unknown: Vec<tlv::Field>| {
        Op::Put(PutOp {
            op_id: [entry; 16],
            hlc: 1 << 20 | entry as u64,
            session_id: [3; 16],
            entry_id: [entry ^ 0x40; 16],
            supersedes: Default::default(),
            entry_type: ty,
            created_ms: 1_700_000_000_000,
            diary_date: (ty == ENTRY_DIARY).then_some(19_700),
            title: format!("entry {entry}"),
            body: body.into(),
            tags: vec!["tag".into()],
            unknown,
        })
    };
    let att = AttachmentRef { seed: [8; 16], parts: 1, size: 5, mime: "image/jpeg".into(), thumb: None, duration_ms: 0, width: 2, height: 2 };
    let folder = put(1, ENTRY_FOLDER, "", vec![]);
    let note = put(2, ENTRY_NOTE, "# Title\n**bold** [link](note:41414141414141414141414141414141)", vec![
        tlv::Field { tag: T_FOLDER, value: vec![0x41; 16] },
        att.field(),
    ]);
    let diary = put(3, ENTRY_DIARY, "day", vec![tlv::Field { tag: T_TRASHED, value: 1u64.to_le_bytes().to_vec() }]);
    let delete = Op::Delete(DeleteOp { op_id: [4; 16], hlc: 2 << 20, session_id: [3; 16], entry_id: [0x43; 16], supersedes: [[3u8; 16]].into() });

    let mut vault = Vec::new();
    let mut record = |mode: u8, payload: &[u8]| {
        vault.push(mode);
        vault.extend_from_slice(&(payload.len() as u16).to_le_bytes());
        vault.extend_from_slice(payload);
    };
    for op in [&folder, &note, &diary, &delete] {
        // Mode 0 makes the object id from the record number; mode 1 carries a whole frame.
        let frame = Frame { kind: Kind::Op, object_id: op.op_id(), part_index: 0, part_count: 1, data: op.encode().into() };
        record(1, &frame.encode()[..]);
    }
    let blob = Frame { kind: Kind::Blob, object_id: [8; 16], part_index: 0, part_count: 1, data: b"jpeg!".to_vec().into() };
    let mut blob_record = vec![8u8; 16];
    blob_record.extend_from_slice(&0u32.to_le_bytes());
    blob_record.extend_from_slice(&blob.encode()[..]);
    record(2, &blob_record);
    record(3, b"pad");

    let mut op_seed = [2u8; 16].to_vec();
    op_seed.extend(note.encode());
    vec![
        ("vault", "entries", vault),
        ("op", "note", op_seed),
        ("frame", "note", Frame { kind: Kind::Op, object_id: [2; 16], part_index: 0, part_count: 1, data: note.encode().into() }.encode().to_vec()),
        ("attachment", "photo", att.encode()),
        ("tlv", "fields", att.encode()),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::random_bytes;

    #[test]
    fn seeds_run_clean_and_reach_the_entries() {
        for (target, _, data) in seeds() {
            match target {
                // The note survives (the diary entry is deleted): the fuzzer starts deep.
                "vault" => assert_eq!(run_vault(&data), Some(1)),
                "op" => op(&data),
                "frame" => frame(&data),
                "attachment" => attachment(&data),
                _ => tlv(&data),
            }
        }
    }

    #[test]
    fn random_inputs_never_panic() {
        for n in 0..400 {
            let d = random_bytes(n * 7 % 900);
            tlv(&d);
            op(&d);
            frame(&d);
            attachment(&d);
            if n % 20 == 0 {
                vault(&d);
            }
        }
    }

    /// Random flips, inserts and cuts of the seeds, on stable Rust and any platform: a quick
    /// look before the real fuzzer. `MUTATIONS=… cargo test --features fuzzing mutate -- --ignored`
    #[test]
    #[ignore]
    fn mutate_seeds() {
        let rounds: usize = std::env::var("MUTATIONS").ok().and_then(|v| v.parse().ok()).unwrap_or(20_000);
        for (target, _, seed) in seeds() {
            for i in 0..rounds {
                if target == "vault" && i % 50 != 0 {
                    continue;
                }
                let mut d = seed.clone();
                let r = random_bytes(8);
                for k in 0..(1 + r[0] as usize % 8) {
                    if d.is_empty() {
                        break;
                    }
                    let at = u32::from_le_bytes(random_bytes(4).try_into().unwrap()) as usize % d.len();
                    match (r[1] as usize + k) % 4 {
                        0 => d[at] ^= 1 << (r[2] % 8),
                        1 => d[at] = r[3],
                        2 => d.insert(at, r[4]),
                        _ => d.truncate(at.max(1)),
                    }
                }
                match target {
                    "vault" => vault(&d),
                    "op" => op(&d),
                    "frame" => frame(&d),
                    "attachment" => attachment(&d),
                    _ => tlv(&d),
                }
            }
        }
    }

    /// `FUZZ_SEED_DIR=… cargo test --features fuzzing write_fuzz_seeds -- --ignored` writes
    /// `<dir>/<target>/<name>` for cargo-fuzz.
    #[test]
    #[ignore]
    fn write_fuzz_seeds() {
        let dir = PathBuf::from(std::env::var("FUZZ_SEED_DIR").expect("FUZZ_SEED_DIR"));
        for (target, name, data) in seeds() {
            std::fs::create_dir_all(dir.join(target)).unwrap();
            std::fs::write(dir.join(target).join(name), data).unwrap();
        }
    }
}
