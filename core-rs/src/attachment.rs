//! Attachments (spec §6.4): an immutable BLOB object per file, its part nonces derived from
//! a random seed, referenced from the entry's PUT by a nested TLV.

use crate::crypto::{self, Key};
use crate::format::*;
use crate::keys::locator;
use crate::tlv::{self, Field};

const A_SEED: u8 = 0x01;
const A_PARTS: u8 = 0x02;
const A_SIZE: u8 = 0x03;
const A_MIME: u8 = 0x04;
const A_THUMB_SEED: u8 = 0x05;
const A_THUMB_PARTS: u8 = 0x06;
const A_DURATION: u8 = 0x07;
const A_WIDTH: u8 = 0x08;
const A_HEIGHT: u8 = 0x09;

#[derive(Clone, Debug, PartialEq)]
pub struct AttachmentRef {
    pub seed: Id,
    pub parts: u32,
    pub size: u64,
    pub mime: String,
    pub thumb: Option<(Id, u32)>,
    pub duration_ms: u64,
    pub width: u32,
    pub height: u32,
}

impl AttachmentRef {
    pub fn encode(&self) -> Vec<u8> {
        let mut w = tlv::Writer::default();
        w.put(A_SEED, &self.seed);
        w.put_u32(A_PARTS, self.parts);
        w.put_u64(A_SIZE, self.size);
        w.put(A_MIME, self.mime.as_bytes());
        if let Some((seed, parts)) = self.thumb {
            w.put(A_THUMB_SEED, &seed);
            w.put_u32(A_THUMB_PARTS, parts);
        }
        if self.duration_ms > 0 {
            w.put_u64(A_DURATION, self.duration_ms);
        }
        // Each on its own: a reference read with only one of them writes back the same.
        if self.width > 0 {
            w.put_u32(A_WIDTH, self.width);
        }
        if self.height > 0 {
            w.put_u32(A_HEIGHT, self.height);
        }
        w.into_bytes()
    }

    pub fn decode(b: &[u8]) -> Option<AttachmentRef> {
        let fields = tlv::parse(b, 0)?;
        let get = |t: u8| fields.iter().find(|f| f.tag == t).map(|f| f.value.as_slice());
        let u32_of = |t: u8| get(t).and_then(|v| v.try_into().ok()).map(u32::from_le_bytes);
        let u64_of = |t: u8| get(t).and_then(|v| v.try_into().ok()).map(u64::from_le_bytes);
        let parts = u32_of(A_PARTS)?;
        if parts == 0 || parts > MAX_BLOB_PARTS {
            return None;
        }
        let thumb = match (get(A_THUMB_SEED), u32_of(A_THUMB_PARTS)) {
            (Some(s), Some(p)) if p > 0 && p <= MAX_BLOB_PARTS => Some((s.try_into().ok()?, p)),
            _ => None,
        };
        Some(AttachmentRef {
            seed: get(A_SEED)?.try_into().ok()?,
            parts,
            size: u64_of(A_SIZE)?,
            mime: String::from_utf8(get(A_MIME)?.to_vec()).ok()?,
            thumb,
            duration_ms: u64_of(A_DURATION).unwrap_or(0),
            width: u32_of(A_WIDTH).unwrap_or(0),
            height: u32_of(A_HEIGHT).unwrap_or(0),
        })
    }

    pub fn field(&self) -> Field {
        Field { tag: T_ATTACHMENT, value: self.encode() }
    }

    /// Every (seed, parts) BLOB this reference keeps alive: the file and its thumbnail.
    pub fn blobs(&self) -> impl Iterator<Item = (Id, u32)> {
        std::iter::once((self.seed, self.parts)).chain(self.thumb)
    }
}

pub fn refs(fields: &[Field]) -> impl Iterator<Item = AttachmentRef> + '_ {
    fields.iter().filter(|f| f.tag == T_ATTACHMENT).filter_map(|f| AttachmentRef::decode(&f.value))
}

/// Nonce of part `i` of the BLOB with `seed`: r = BLAKE2b(K_blob_nonce, seed ‖ LE32(i)), then
/// the usual locator under K_blob_loc. Deterministic, so parts are found without being stored.
pub fn part_nonce(blob_nonce: &Key, blob_loc: &Key, seed: &Id, i: u32) -> Nonce {
    let mut msg = [0u8; 20];
    msg[..16].copy_from_slice(seed);
    msg[16..].copy_from_slice(&i.to_le_bytes());
    let r = crypto::keyed_hash16(blob_nonce, &msg);
    let mut n = [0u8; NONCE_LEN];
    n[..R_LEN].copy_from_slice(&r);
    n[R_LEN..].copy_from_slice(&locator::of(blob_loc, &r));
    n
}

pub fn parts_for(len: usize) -> u32 {
    len.div_ceil(FRAME_CAP).max(1) as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ref_round_trip() {
        let r = AttachmentRef {
            seed: [7; 16],
            parts: 3,
            size: 10_000,
            mime: "image/jpeg".into(),
            thumb: Some(([9; 16], 1)),
            duration_ms: 0,
            width: 1920,
            height: 1080,
        };
        assert_eq!(AttachmentRef::decode(&r.encode()), Some(r));
        assert!(AttachmentRef::decode(&[1, 2, 3]).is_none());
    }

    /// Found by fuzzing: a height without a width was dropped when the reference was rewritten.
    #[test]
    fn height_without_width_survives_a_rewrite() {
        let r = AttachmentRef { seed: [8; 16], parts: 1, size: 5, mime: "image/jpeg".into(), thumb: None, duration_ms: 0, width: 0, height: 2 };
        assert_eq!(AttachmentRef::decode(&r.encode()), Some(r));
    }
}
