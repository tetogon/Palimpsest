//! Key hierarchy (spec §3.1–3.4): password keys, vault subkeys, locators, block sealing.

use crate::crypto::{self, Key};
use crate::format::*;
use unicode_normalization::UnicodeNormalization;
use zeroize::{Zeroize, Zeroizing};

pub struct PasswordKeys {
    pub loc: Key,
    pub wrap: Key,
}

impl PasswordKeys {
    pub fn derive(password: &str, salt: &[u8]) -> Result<PasswordKeys, argon2::Error> {
        let mut pw: String = password.nfc().collect();
        let p = crypto::argon2id(pw.as_bytes(), salt);
        pw.zeroize();
        let p = p?;
        Ok(PasswordKeys { loc: crypto::kdf(&p, 1, CTX_PW), wrap: crypto::kdf(&p, 2, CTX_PW) })
    }
}

pub struct VaultKeys {
    pub enc: Key,
    pub meta_loc: Key,
    pub blob_loc: Key,
    pub pad_loc: Key,
    #[allow(dead_code)] // reserved for attachments (spec §6.4)
    pub blob_nonce: Key,
}

impl VaultKeys {
    pub fn derive(v: &Key) -> VaultKeys {
        VaultKeys {
            enc: crypto::kdf(v, 1, CTX_VK),
            meta_loc: crypto::kdf(v, 2, CTX_VK),
            blob_loc: crypto::kdf(v, 3, CTX_VK),
            pad_loc: crypto::kdf(v, 4, CTX_VK),
            blob_nonce: crypto::kdf(v, 5, CTX_VK),
        }
    }

    pub fn own_locators(&self) -> [&Key; 3] {
        [&self.meta_loc, &self.blob_loc, &self.pad_loc]
    }
}

/// nonce = r ‖ BLAKE2b(key, r)[0..8] (spec §3.3).
pub mod locator {
    use super::*;

    pub fn of(key: &Key, r: &[u8]) -> [u8; LOC_LEN] {
        let h = crypto::keyed_hash16(key, r);
        let mut out = [0u8; LOC_LEN];
        out.copy_from_slice(&h[..LOC_LEN]);
        out
    }

    pub fn nonce(key: &Key) -> Nonce {
        let mut n = [0u8; NONCE_LEN];
        n[..R_LEN].copy_from_slice(&crypto::random_bytes(R_LEN));
        let loc = of(key, &n[..R_LEN]);
        n[R_LEN..].copy_from_slice(&loc);
        n
    }

    pub fn matches(key: &Key, nonce: &[u8]) -> bool {
        let expected = of(key, &nonce[..R_LEN]);
        expected.iter().zip(&nonce[R_LEN..NONCE_LEN]).fold(0u8, |d, (a, b)| d | (a ^ b)) == 0
    }
}

/// Block-level sealing with the container salt in AAD (spec §3.4).
pub struct BlockCipher {
    ad: Vec<u8>,
}

impl BlockCipher {
    pub fn new(salt: &[u8]) -> BlockCipher {
        BlockCipher { ad: [AAD_PREFIX, salt].concat() }
    }

    /// `plaintext` is zeroed by the caller's Zeroizing wrapper; frames can carry key bytes.
    pub fn seal(&self, key: &Key, nonce: &Nonce, plaintext: &[u8]) -> Vec<u8> {
        assert_eq!(plaintext.len(), PT_LEN);
        let ct = crypto::seal(key, nonce, plaintext, &self.ad);
        let mut block = Vec::with_capacity(BLOCK_SIZE);
        block.extend_from_slice(nonce);
        block.extend_from_slice(&ct);
        block
    }

    pub fn open(&self, key: &Key, block: &[u8]) -> Option<Zeroizing<Vec<u8>>> {
        let nonce: &Nonce = block[..NONCE_LEN].try_into().ok()?;
        crypto::open(key, nonce, &block[NONCE_LEN..], &self.ad).filter(|pt| pt.len() == PT_LEN)
    }

    pub fn chaff(&self) -> Vec<u8> {
        crypto::random_bytes(BLOCK_SIZE)
    }

    /// Own placeholder left behind by purge (spec §6.5).
    pub fn pad(&self, pad_loc: &Key) -> Vec<u8> {
        let mut b = locator::nonce(pad_loc).to_vec();
        b.extend_from_slice(&crypto::random_bytes(BLOCK_SIZE - NONCE_LEN));
        b
    }
}
