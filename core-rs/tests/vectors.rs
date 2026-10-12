//! Cross-implementation vectors produced by the Kotlin + libsodium reference core
//! (core/src/test/.../GenerateVectors.kt). This crate must reproduce every primitive
//! byte for byte and open the reference containers.

use palimpsest_core::crypto::{self, Key};
use palimpsest_core::format::*;
use palimpsest_core::keys::{locator, PasswordKeys};
use palimpsest_core::Container;
use serde_json::Value;
use std::fs;
use std::path::PathBuf;

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../test-vectors")
}

fn json(name: &str) -> Value {
    serde_json::from_str(&fs::read_to_string(dir().join(name)).unwrap()).unwrap()
}

fn h(v: &Value, k: &str) -> Vec<u8> {
    hex::decode(v[k].as_str().unwrap()).unwrap()
}

/// Copy of a reference container in a temp dir, so tests never modify the fixture.
fn copy(name: &str) -> (tempfile::TempDir, String) {
    let tmp = tempfile::tempdir().unwrap();
    let path = tmp.path().join(name);
    fs::copy(dir().join(name), &path).unwrap();
    (tmp, path.to_string_lossy().into())
}

#[test]
fn primitives_match_libsodium() {
    let v = json("primitives.json");
    let salt = h(&v, "salt");
    let pw = v["password"].as_str().unwrap();
    assert_eq!(crypto::argon2id(pw.as_bytes(), &salt).unwrap().bytes()[..], h(&v, "argon2id")[..], "argon2id");

    let pk = PasswordKeys::derive(pw, &salt).unwrap();
    assert_eq!(pk.loc.bytes()[..], h(&v, "pw_loc")[..], "pw_loc");
    assert_eq!(pk.wrap.bytes()[..], h(&v, "pw_wrap")[..], "pw_wrap");

    let master = Key::from_bytes(&h(&v, "master"));
    assert_eq!(crypto::kdf(&master, 1, CTX_VK).bytes()[..], h(&v, "kdf_vk_1")[..], "kdf 1");
    assert_eq!(crypto::kdf(&master, 5, CTX_VK).bytes()[..], h(&v, "kdf_vk_5")[..], "kdf 5");
    assert_eq!(crypto::kdf(&master, 2, CTX_PW).bytes()[..], h(&v, "kdf_pw_2")[..], "kdf pw 2");

    let msg = h(&v, "hash_msg");
    assert_eq!(crypto::keyed_hash16(&master, &msg)[..], h(&v, "keyed_hash16")[..], "generichash");
    let mut expected_nonce = msg.clone();
    expected_nonce.extend_from_slice(&locator::of(&master, &msg));
    assert_eq!(expected_nonce, h(&v, "locator_nonce"), "locator");

    let nonce: [u8; NONCE_LEN] = h(&v, "aead_nonce").try_into().unwrap();
    let (pt, ad) = (h(&v, "aead_plaintext"), h(&v, "aead_ad"));
    let ct = crypto::seal(&master, &nonce, &pt, &ad);
    assert_eq!(ct, h(&v, "aead_ciphertext"), "xchacha20poly1305");
    assert_eq!(crypto::open(&master, &nonce, &ct, &ad).unwrap()[..], pt[..]);
}

#[test]
fn opens_reference_two_vault_container() {
    let v = json("two-vaults.json");
    let (_tmp, path) = copy("two-vaults.bin");
    let c = Container::open_or_create(path).unwrap();
    for (pw, expected) in v["vaults"].as_object().unwrap() {
        let s = c.unlock(pw.clone()).unwrap().unwrap_or_else(|| panic!("{pw} must open"));
        let got = s.entries().unwrap();
        let want = expected.as_array().unwrap();
        assert_eq!(got.len(), want.len(), "{pw}: entry count");
        for (g, w) in got.iter().zip(want) {
            assert_eq!(g.entry_id, w["entry_id"].as_str().unwrap(), "{pw}: order / id");
            assert_eq!(g.title, w["title"].as_str().unwrap());
            assert_eq!(g.diary_date, w["diary_date"].as_i64().map(|d| d as i32));
            assert_eq!(g.created_ms, w["created_ms"].as_i64().unwrap());
            let detail = s.entry(g.entry_id.clone()).unwrap().unwrap();
            assert_eq!(detail.body, w["body"].as_str().unwrap(), "{pw}: body of {}", g.title);
        }
        s.lock().unwrap();
    }
    for pw in v["wrong_passwords"].as_array().unwrap() {
        assert!(c.unlock(pw.as_str().unwrap().into()).unwrap().is_none());
    }
}

#[test]
fn reference_armed_wipe_fires() {
    let (_tmp, path) = copy("armed-wipe.bin");
    let c = Container::open_or_create(path).unwrap();
    let titles = |pw: &str| c.unlock(pw.into()).unwrap().map(|s| s.entries().unwrap().into_iter().map(|e| e.title).collect::<Vec<_>>());
    assert_eq!(titles("R").unwrap(), ["real entry"]);
    assert_eq!(titles("D").unwrap(), ["decoy entry"]);
    assert!(titles("R").is_none());
    assert_eq!(titles("D").unwrap(), ["decoy entry"]);
}
