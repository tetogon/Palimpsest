//! The only primitives the format uses (spec §3), on RustCrypto crates. Outputs are
//! byte-identical to the libsodium calls named in the spec; tests/vectors.rs proves it
//! against vectors produced by the libsodium reference implementation.

use crate::format::{ARGON_MEM_KIB, ARGON_OPS, KEY_LEN, NONCE_LEN, TAG_LEN};
use argon2::{Algorithm, Argon2, Params, Version};
use blake2::digest::consts::{U16, U32};
use blake2::digest::{FixedOutput, KeyInit, Mac, Update};
use blake2::Blake2bMac;
use chacha20poly1305::aead::{Aead, Payload};
use chacha20poly1305::{XChaCha20Poly1305, XNonce};
use zeroize::{Zeroize, Zeroizing};

/// 32-byte key on the heap, zeroed on drop. Boxed so moves never leave copies behind.
pub struct Key(Box<Zeroizing<[u8; KEY_LEN]>>);

impl Key {
    pub fn from_bytes(bytes: &[u8]) -> Key {
        let mut k = Box::new(Zeroizing::new([0u8; KEY_LEN]));
        k.copy_from_slice(bytes);
        Key(k)
    }

    pub fn random() -> Key {
        let mut k = Box::new(Zeroizing::new([0u8; KEY_LEN]));
        getrandom::getrandom(&mut k[..]).expect("OS random generator unavailable");
        Key(k)
    }

    pub fn bytes(&self) -> &[u8; KEY_LEN] {
        &self.0
    }

    pub fn duplicate(&self) -> Key {
        Key::from_bytes(self.bytes())
    }
}

/// libsodium crypto_pwhash, ALG_ARGON2ID13, 32-byte output.
pub fn argon2id(password: &[u8], salt: &[u8]) -> Result<Key, argon2::Error> {
    let params = Params::new(ARGON_MEM_KIB, ARGON_OPS, 1, Some(KEY_LEN))?;
    let mut out = Box::new(Zeroizing::new([0u8; KEY_LEN]));
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params).hash_password_into(password, salt, &mut out[..])?;
    Ok(Key(out))
}

/// libsodium crypto_kdf_derive_from_key: BLAKE2b(key = master, salt = LE64(id) ‖ 0⁸,
/// personal = ctx ‖ 0⁸, empty message), 32-byte output.
pub fn kdf(master: &Key, subkey_id: u64, context: &[u8; 8]) -> Key {
    let mut salt = [0u8; 16];
    salt[..8].copy_from_slice(&subkey_id.to_le_bytes());
    let mut personal = [0u8; 16];
    personal[..8].copy_from_slice(context);
    let mac = Blake2bMac::<U32>::new_with_salt_and_personal(master.bytes(), &salt, &personal)
        .expect("valid BLAKE2b parameters");
    let mut out = mac.finalize_fixed();
    let key = Key::from_bytes(&out);
    out.as_mut_slice().zeroize();
    key
}

/// libsodium crypto_generichash with a 32-byte key and 16-byte output.
pub fn keyed_hash16(key: &Key, message: &[u8]) -> [u8; 16] {
    let mut mac = <Blake2bMac<U16> as KeyInit>::new_from_slice(key.bytes()).expect("valid BLAKE2b key");
    Update::update(&mut mac, message);
    let out = Mac::finalize(mac).into_bytes();
    let mut r = [0u8; 16];
    r.copy_from_slice(&out);
    r
}

/// crypto_aead_xchacha20poly1305_ietf_encrypt: ciphertext ‖ tag.
pub fn seal(key: &Key, nonce: &[u8; NONCE_LEN], plaintext: &[u8], ad: &[u8]) -> Vec<u8> {
    let cipher = XChaCha20Poly1305::new(key.bytes().into());
    let ct = cipher
        .encrypt(XNonce::from_slice(nonce), Payload { msg: plaintext, aad: ad })
        .expect("XChaCha20-Poly1305 encryption cannot fail for block-sized input");
    debug_assert_eq!(ct.len(), plaintext.len() + TAG_LEN);
    ct
}

/// None when authentication fails.
pub fn open(key: &Key, nonce: &[u8; NONCE_LEN], ciphertext: &[u8], ad: &[u8]) -> Option<Zeroizing<Vec<u8>>> {
    let cipher = XChaCha20Poly1305::new(key.bytes().into());
    cipher
        .decrypt(XNonce::from_slice(nonce), Payload { msg: ciphertext, aad: ad })
        .ok()
        .map(Zeroizing::new)
}

pub fn random_bytes(n: usize) -> Vec<u8> {
    let mut b = vec![0u8; n];
    if n > 0 {
        getrandom::getrandom(&mut b).expect("OS random generator unavailable");
    }
    b
}

/// Uniform in [0, bound) by rejection sampling, like libsodium randombytes_uniform.
pub fn random_below(bound: u32) -> u32 {
    assert!(bound > 0);
    let min = bound.wrapping_neg() % bound;
    loop {
        let mut b = [0u8; 4];
        getrandom::getrandom(&mut b).expect("OS random generator unavailable");
        let r = u32::from_le_bytes(b);
        if r >= min {
            return r % bound;
        }
    }
}
