//! Constants of container format v1 (docs/format-spec.md §2).

pub const BLOCK_SIZE: usize = 4096;
pub const SALT_LEN: usize = 16;
pub const R_LEN: usize = 16;
pub const LOC_LEN: usize = 8;
pub const NONCE_LEN: usize = R_LEN + LOC_LEN;
pub const TAG_LEN: usize = 16;
pub const PT_LEN: usize = BLOCK_SIZE - NONCE_LEN - TAG_LEN;
pub const FRAME_HDR: usize = 27;
pub const FRAME_CAP: usize = PT_LEN - FRAME_HDR;
pub const KEY_LEN: usize = 32;

/// Same as libsodium crypto_pwhash(opslimit = 3, memlimit = 64 MiB).
pub const ARGON_OPS: u32 = 3;
pub const ARGON_MEM_KIB: u32 = 64 * 1024;

pub const MAX_OP_PARTS: u32 = 256;
pub const MAX_BLOB_PARTS: u32 = 65_536;

pub const AAD_PREFIX: &[u8] = b"plmp1-block";
pub const CTX_PW: &[u8; 8] = b"plmp1-pw";
pub const CTX_VK: &[u8; 8] = b"plmp1-vk";

pub const ENTRY_DIARY: u8 = 1;
pub const ENTRY_NOTE: u8 = 2;
pub const ENTRY_TODO: u8 = 3;
/// A folder: title is its name. Notes point to it with [T_FOLDER].
pub const ENTRY_FOLDER: u8 = 4;
pub const ENTRY_SETTINGS: u8 = 0xFF;

/// PUT TLV attributes (spec §6.3). Absent = default.
/// To-do item is done: u8 1.
pub const T_DONE: u8 = 0x10;
/// Folder entry id, 16 bytes.
pub const T_FOLDER: u8 = 0x11;
/// Card colour, u8 1..=8 (0 or absent = none).
pub const T_COLOR: u8 = 0x12;
/// Pinned to the top: u8 1.
pub const T_PINNED: u8 = 0x13;
/// In the trash since this unix time in ms, u64.
pub const T_TRASHED: u8 = 0x14;
/// Place of a folder in the folder row, u32 LE (absent = after the placed ones, by name).
pub const T_ORDER: u8 = 0x15;

/// PUT TLV: attachment reference, repeatable, nested TLV (spec §6.4).
pub const T_ATTACHMENT: u8 = 0x09;

/// App-level cap on one attachment (the format allows ~252 MiB).
pub const MAX_ATTACHMENT_BYTES: usize = 25 * 1024 * 1024;

/// Trashed entries are deleted for good this long after trashing, on unlock.
pub const TRASH_RETENTION_MS: u64 = 30 * 24 * 60 * 60 * 1000;

/// Vault settings are an entry with this id (spec §6.2).
pub const SETTINGS_ID: Id = [0; 16];

/// settings TLV: purge_mode, absent = immediate, [1] = manual (spec §8.2, §10).
pub const T_PURGE_MODE: u8 = 0x41;

/// settings TLV: locators of the vault to shred on next unlock (spec §10.1).
pub const T_WIPE_TARGET: u8 = 0x45;

/// 16-byte object / entry / session id. Ord is unsigned lexicographic, as in the spec.
pub type Id = [u8; 16];

/// 24-byte block id (its nonce).
pub type Nonce = [u8; NONCE_LEN];

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn parse_id(s: &str) -> Option<Id> {
    if s.len() != 32 {
        return None;
    }
    let mut id = [0u8; 16];
    for (i, out) in id.iter_mut().enumerate() {
        *out = u8::from_str_radix(s.get(2 * i..2 * i + 2)?, 16).ok()?;
    }
    Some(id)
}
