//! Plaintext layout of one block (spec §5) and object reassembly.

use crate::format::*;
use std::collections::HashMap;
use zeroize::Zeroizing;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Keywrap,
    Op,
    Blob,
}

impl Kind {
    pub fn code(self) -> u8 {
        match self {
            Kind::Keywrap => 0x01,
            Kind::Op => 0x02,
            Kind::Blob => 0x04,
        }
    }

    fn from_code(c: u8) -> Option<Kind> {
        match c {
            0x01 => Some(Kind::Keywrap),
            0x02 => Some(Kind::Op),
            0x04 => Some(Kind::Blob),
            _ => None,
        }
    }

    fn max_parts(self) -> u32 {
        match self {
            Kind::Keywrap => 1,
            Kind::Op => MAX_OP_PARTS,
            Kind::Blob => MAX_BLOB_PARTS,
        }
    }
}

pub struct Frame {
    pub kind: Kind,
    pub object_id: Id,
    pub part_index: u32,
    pub part_count: u32,
    pub data: Zeroizing<Vec<u8>>,
}

impl Frame {
    pub fn encode(&self) -> Zeroizing<Vec<u8>> {
        assert!(self.data.len() <= FRAME_CAP);
        let mut out = Zeroizing::new(vec![0u8; PT_LEN]);
        out[0] = self.kind.code();
        out[1..17].copy_from_slice(&self.object_id);
        out[17..21].copy_from_slice(&self.part_index.to_le_bytes());
        out[21..25].copy_from_slice(&self.part_count.to_le_bytes());
        out[25..27].copy_from_slice(&(self.data.len() as u16).to_le_bytes());
        out[FRAME_HDR..FRAME_HDR + self.data.len()].copy_from_slice(&self.data);
        out
    }

    /// None for any frame that violates spec §5.1.
    pub fn decode(pt: &[u8]) -> Option<Frame> {
        if pt.len() != PT_LEN {
            return None;
        }
        let kind = Kind::from_code(pt[0])?;
        let object_id: Id = pt[1..17].try_into().ok()?;
        let index = u32::from_le_bytes(pt[17..21].try_into().ok()?);
        let count = u32::from_le_bytes(pt[21..25].try_into().ok()?);
        let len = u16::from_le_bytes(pt[25..27].try_into().ok()?) as usize;
        if count == 0 || count > kind.max_parts() || index >= count || len > FRAME_CAP {
            return None;
        }
        if index < count - 1 && len != FRAME_CAP {
            return None;
        }
        if pt[FRAME_HDR + len..].iter().any(|&b| b != 0) {
            return None;
        }
        Some(Frame {
            kind,
            object_id,
            part_index: index,
            part_count: count,
            data: Zeroizing::new(pt[FRAME_HDR..FRAME_HDR + len].to_vec()),
        })
    }

    pub fn split(kind: Kind, object_id: Id, data: &[u8]) -> Vec<Frame> {
        let count = data.len().div_ceil(FRAME_CAP).max(1);
        assert!(count as u32 <= kind.max_parts(), "object too large: {count} parts");
        (0..count)
            .map(|i| {
                let from = i * FRAME_CAP;
                let to = (from + FRAME_CAP).min(data.len());
                Frame {
                    kind,
                    object_id,
                    part_index: i as u32,
                    part_count: count as u32,
                    data: Zeroizing::new(data[from..to].to_vec()),
                }
            })
            .collect()
    }
}

pub struct Assembled {
    pub object_id: Id,
    pub data: Zeroizing<Vec<u8>>,
    pub blocks: Vec<u32>,
}

/// Collects frames into objects (spec §5.3). Incomplete and corrupt objects are never returned.
#[derive(Default)]
pub struct Assembler {
    pending: HashMap<Id, Pending>,
}

struct Pending {
    kind: Kind,
    parts: Vec<Option<Zeroizing<Vec<u8>>>>,
    blocks: Vec<u32>,
    corrupt: bool,
}

impl Assembler {
    pub fn add(&mut self, frame: Frame, block_index: u32) {
        let p = self.pending.entry(frame.object_id).or_insert_with(|| Pending {
            kind: frame.kind,
            parts: (0..frame.part_count).map(|_| None).collect(),
            blocks: Vec::new(),
            corrupt: false,
        });
        p.blocks.push(block_index);
        if p.kind != frame.kind || p.parts.len() != frame.part_count as usize {
            p.corrupt = true;
            return;
        }
        let slot = &mut p.parts[frame.part_index as usize];
        if let Some(existing) = slot {
            if existing[..] != frame.data[..] {
                p.corrupt = true;
            }
        }
        *slot = Some(frame.data);
    }

    pub fn complete(self) -> Vec<Assembled> {
        self.pending
            .into_iter()
            .filter(|(_, p)| !p.corrupt && p.parts.iter().all(Option::is_some))
            .map(|(id, p)| {
                let mut data = Zeroizing::new(Vec::new());
                for part in p.parts.iter().flatten() {
                    data.extend_from_slice(part);
                }
                Assembled { object_id: id, data, blocks: p.blocks }
            })
            .collect()
    }
}
