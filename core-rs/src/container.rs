//! A container holds any number of vaults and never knows how many (spec §1).

use crate::chaff;
use crate::crypto::{random_below, random_bytes, Key};
use crate::format::*;
use crate::frame::{Frame, Kind};
use crate::heap::Heap;
use crate::keys::{locator, BlockCipher, PasswordKeys, VaultKeys};
use crate::session::{random_id, Inner, Session};
use std::collections::BTreeSet;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};
use zeroize::Zeroizing;

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CoreError {
    #[error("I/O error: {0}")]
    Io(String),
    #[error("vault is locked")]
    Locked,
    #[error("key derivation failed: {0}")]
    Kdf(String),
    #[error("invalid argument: {0}")]
    Invalid(String),
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::Io(e.to_string())
    }
}

impl From<argon2::Error> for CoreError {
    fn from(e: argon2::Error) -> Self {
        CoreError::Kdf(e.to_string())
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;

pub(crate) fn guard<T>(m: &Mutex<T>) -> Result<MutexGuard<'_, T>> {
    m.lock().map_err(|_| CoreError::Invalid("internal lock poisoned".into()))
}

#[derive(uniffi::Enum, Debug, PartialEq, Eq)]
pub enum ChangePasswordResult {
    Changed,
    WrongPassword,
    PasswordTaken,
}

#[derive(uniffi::Enum, Debug, PartialEq, Eq)]
pub enum ArmWipeResult {
    Armed,
    NotFound,
    SameVault,
}

#[derive(uniffi::Enum, Debug, PartialEq, Eq)]
pub enum ImportResult {
    /// No vault in the file opens with that password.
    NotFound,
    /// A new vault was made here, opened by the same password.
    Created { entries: u32 },
    /// The password already opened a vault here; the file's entries were merged into it.
    Merged { entries: u32 },
}

/// Replaces the container file at `path` with the backup at `source` (spec §11.7). No
/// Container for `path` may be in use. Only the shape is checked: nothing in a container can
/// be read without a password.
#[uniffi::export]
pub fn restore_container(path: String, source: String) -> Result<()> {
    let len = std::fs::metadata(&source)?.len();
    if len < 2 * BLOCK_SIZE as u64 || len % BLOCK_SIZE as u64 != 0 {
        return Err(CoreError::Invalid("not a container".into()));
    }
    Ok(Heap::new(path).replace_with(std::path::Path::new(&source))?)
}

/// Immutable per-container data plus the file, behind one lock so a session and the
/// background chaff job never interleave writes. Lock order: heap, then session.
pub(crate) struct Shared {
    pub heap: Mutex<Heap>,
    pub cipher: BlockCipher,
    pub salt: [u8; SALT_LEN],
}

#[derive(uniffi::Object)]
pub struct Container {
    shared: Arc<Shared>,
}

struct Keywrap {
    block_index: u32,
    vault_id: Id,
    v: Key,
}

/// Appends in random order so position never separates chaff from data. Returns each input's index.
pub(crate) fn append_shuffled(heap: &Heap, blocks: Vec<Vec<u8>>) -> Result<Vec<u32>> {
    let mut order: Vec<usize> = (0..blocks.len()).collect();
    for i in (1..order.len()).rev() {
        let j = random_below(i as u32 + 1) as usize;
        order.swap(i, j);
    }
    let shuffled: Vec<Vec<u8>> = order.iter().map(|&k| blocks[k].clone()).collect();
    let base = heap.append(&shuffled)?;
    let mut positions = vec![0u32; blocks.len()];
    for (slot, &source) in order.iter().enumerate() {
        positions[source] = base + slot as u32;
    }
    Ok(positions)
}

pub(crate) fn chaff_blocks(cipher: &BlockCipher, n: usize) -> Vec<Vec<u8>> {
    (0..n).map(|_| cipher.chaff()).collect()
}

fn now_hlc() -> u64 {
    let ms = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
    ms << 16
}

fn keywrap_block(cipher: &BlockCipher, pk: &PasswordKeys, vault_id: &Id, v: &Key) -> Vec<u8> {
    let mut data = Zeroizing::new(Vec::with_capacity(16 + KEY_LEN + 8));
    data.extend_from_slice(vault_id);
    data.extend_from_slice(v.bytes());
    data.extend_from_slice(&now_hlc().to_le_bytes());
    let frame = Frame { kind: Kind::Keywrap, object_id: random_id(), part_index: 0, part_count: 1, data };
    cipher.seal(&pk.wrap, &locator::nonce(&pk.loc), &frame.encode())
}

fn find_keywrap(heap: &Heap, cipher: &BlockCipher, pk: &PasswordKeys, nonces: &[Nonce]) -> Result<Option<Keywrap>> {
    for (i, n) in nonces.iter().enumerate() {
        if !locator::matches(&pk.loc, n) {
            continue;
        }
        let Some(pt) = cipher.open(&pk.wrap, &heap.read_block(i as u32)?) else { continue };
        let Some(frame) = Frame::decode(&pt) else { continue };
        if frame.kind != Kind::Keywrap || frame.data.len() != 16 + KEY_LEN + 8 {
            continue;
        }
        return Ok(Some(Keywrap {
            block_index: i as u32,
            vault_id: frame.data[..16].try_into().expect("16 bytes"),
            v: Key::from_bytes(&frame.data[16..16 + KEY_LEN]),
        }));
    }
    Ok(None)
}

/// Overwrites in place, with fresh random bytes indistinguishable from chaff, every block
/// matching any of `locators` plus `extra`. File size does not change.
fn shred(heap: &Heap, cipher: &BlockCipher, locators: &[&Key], extra: &[u32]) -> Result<()> {
    let nonces = heap.read_nonces()?;
    let mut targets: BTreeSet<u32> = (0..nonces.len() as u32)
        .filter(|&i| locators.iter().any(|k| locator::matches(k, &nonces[i as usize])))
        .collect();
    targets.extend(extra);
    let writes: Vec<(u32, Vec<u8>)> = targets.into_iter().map(|i| (i, cipher.chaff())).collect();
    heap.overwrite(&writes)?;
    Ok(())
}

/// Writes a KEYWRAP for a new vault key and loads the new, empty vault.
fn new_vault(heap: &Heap, cipher: &BlockCipher, pk: &PasswordKeys) -> Result<Inner> {
    let v = Key::random();
    let mut batch = vec![keywrap_block(cipher, pk, &random_id(), &v)];
    batch.extend(chaff_blocks(cipher, chaff::on_commit(1)));
    append_shuffled(heap, batch)?;
    drop(v);
    let nonces = heap.read_nonces()?;
    let kw = find_keywrap(heap, cipher, pk, &nonces)?.ok_or_else(|| CoreError::Io("new vault not found".into()))?;
    Inner::load(heap, cipher, kw.vault_id, kw.block_index, kw.v, &nonces)
}

impl Container {
    fn derive(&self, password: &str) -> Result<PasswordKeys> {
        Ok(PasswordKeys::derive(password, &self.shared.salt)?)
    }

    fn session(&self, inner: Inner) -> Arc<Session> {
        Arc::new(Session { shared: self.shared.clone(), inner: Mutex::new(inner) })
    }
}

#[uniffi::export]
impl Container {
    #[uniffi::constructor]
    pub fn open_or_create(path: String) -> Result<Arc<Container>> {
        let heap = Heap::new(path);
        let created = !heap.exists();
        if created {
            heap.create(&random_bytes(BLOCK_SIZE))?;
        } else {
            heap.repair_tail()?;
        }
        let salt = heap.read_salt()?;
        let cipher = BlockCipher::new(&salt);
        if created {
            append_shuffled(&heap, chaff_blocks(&cipher, chaff::on_create()))?;
        }
        Ok(Arc::new(Container { shared: Arc::new(Shared { heap: Mutex::new(heap), cipher, salt }) }))
    }

    /// The session, or None if no vault opens with this password. An armed wipe rule
    /// (spec §10.1) shreds its target and removes itself before the session is returned.
    pub fn unlock(&self, password: String) -> Result<Option<Arc<Session>>> {
        let password = Zeroizing::new(password);
        let pk = self.derive(&password)?;
        let heap = guard(&self.shared.heap)?;
        let cipher = &self.shared.cipher;
        let nonces = heap.read_nonces()?;
        let Some(kw) = find_keywrap(&heap, cipher, &pk, &nonces)? else { return Ok(None) };
        let mut inner = Inner::load(&heap, cipher, kw.vault_id, kw.block_index, kw.v, &nonces)?;
        if let Some(target) = inner.wipe_target() {
            shred(&heap, cipher, &target.iter().collect::<Vec<_>>(), &[])?;
            inner.set_wipe_target(&heap, cipher, None)?;
        }
        inner.purge_expired_trash(&heap, cipher, now_hlc() >> 16)?;
        Ok(Some(self.session(inner)))
    }

    /// None if the password already opens a vault (spec §11.2).
    pub fn create_vault(&self, password: String) -> Result<Option<Arc<Session>>> {
        let password = Zeroizing::new(password);
        let pk = self.derive(&password)?;
        let heap = guard(&self.shared.heap)?;
        let cipher = &self.shared.cipher;
        if find_keywrap(&heap, cipher, &pk, &heap.read_nonces()?)?.is_some() {
            return Ok(None);
        }
        Ok(Some(self.session(new_vault(&heap, cipher, &pk)?)))
    }

    /// Copies the whole container file to `dest`: every vault, and the chaff (spec §11.7).
    pub fn backup_to(&self, dest: String) -> Result<()> {
        let heap = guard(&self.shared.heap)?;
        Ok(heap.copy_to(std::path::Path::new(&dest))?)
    }

    /// Writes a new container file at `dest` holding a copy of `session`'s vault under a
    /// fresh vault key, opened by `password` (spec §11.7). Returns the entries copied.
    pub fn export_vault(&self, session: Arc<Session>, dest: String, password: String) -> Result<u32> {
        let password = Zeroizing::new(password);
        if Heap::new(&dest).exists() {
            return Err(CoreError::Invalid("destination exists".into()));
        }
        let target = Container::open_or_create(dest)?;
        let heap = guard(&self.shared.heap)?;
        let mut src = guard(&session.inner)?;
        src.keys()?;
        let theap = guard(&target.shared.heap)?;
        let tcipher = &target.shared.cipher;
        let mut copy = new_vault(&theap, tcipher, &target.derive(&password)?)?;
        let n = copy.import_from(&theap, tcipher, &mut src, &heap, &self.shared.cipher)?;
        copy.close(&theap, tcipher)?;
        Ok(n)
    }

    /// Copies the vault that `password` opens in the container file `source` into this
    /// container: into the vault the same password opens here, or else into a new one
    /// (spec §11.7). `current` is the app's open session; if it is that vault, it is used so
    /// it sees the new entries. Wipe rules do not fire. `source` gets written to (purge,
    /// chaff): pass a copy.
    pub fn import_vault(&self, source: String, password: String, current: Option<Arc<Session>>) -> Result<ImportResult> {
        let password = Zeroizing::new(password);
        let src_heap = Heap::new(&source);
        if !src_heap.exists() {
            return Ok(ImportResult::NotFound);
        }
        src_heap.repair_tail()?;
        let src_salt = src_heap.read_salt()?;
        let src_cipher = BlockCipher::new(&src_salt);
        let src_nonces = src_heap.read_nonces()?;
        let Some(kw) = find_keywrap(&src_heap, &src_cipher, &PasswordKeys::derive(&password, &src_salt)?, &src_nonces)? else {
            return Ok(ImportResult::NotFound);
        };
        let mut src = Inner::load(&src_heap, &src_cipher, kw.vault_id, kw.block_index, kw.v, &src_nonces)?;

        let pk = self.derive(&password)?;
        let heap = guard(&self.shared.heap)?;
        let cipher = &self.shared.cipher;
        let nonces = heap.read_nonces()?;
        let result = match find_keywrap(&heap, cipher, &pk, &nonces)? {
            Some(local) => {
                let open = current.filter(|s| guard(&s.inner).map(|i| i.vault_id == local.vault_id && i.keys().is_ok()).unwrap_or(false));
                let entries = match open {
                    Some(s) => guard(&s.inner)?.import_from(&heap, cipher, &mut src, &src_heap, &src_cipher)?,
                    None => {
                        let mut dst = Inner::load(&heap, cipher, local.vault_id, local.block_index, local.v, &nonces)?;
                        let n = dst.import_from(&heap, cipher, &mut src, &src_heap, &src_cipher)?;
                        dst.close(&heap, cipher)?;
                        n
                    }
                };
                ImportResult::Merged { entries }
            }
            None => {
                let mut dst = new_vault(&heap, cipher, &pk)?;
                let entries = dst.import_from(&heap, cipher, &mut src, &src_heap, &src_cipher)?;
                dst.close(&heap, cipher)?;
                ImportResult::Created { entries }
            }
        };
        src.close(&src_heap, &src_cipher)?;
        Ok(result)
    }

    /// New KEYWRAP with the same V; the old one is overwritten with chaff (spec §11.4).
    pub fn change_password(&self, session: Arc<Session>, old: String, new: String) -> Result<ChangePasswordResult> {
        let (old, new) = (Zeroizing::new(old), Zeroizing::new(new));
        let old_pk = self.derive(&old)?;
        let new_pk = self.derive(&new)?;
        let heap = guard(&self.shared.heap)?;
        let mut inner = guard(&session.inner)?;
        inner.keys()?;
        let cipher = &self.shared.cipher;
        let nonces = heap.read_nonces()?;
        let kw = match find_keywrap(&heap, cipher, &old_pk, &nonces)? {
            Some(kw) if kw.vault_id == inner.vault_id => kw,
            _ => return Ok(ChangePasswordResult::WrongPassword),
        };
        if find_keywrap(&heap, cipher, &new_pk, &nonces)?.is_some() {
            return Ok(ChangePasswordResult::PasswordTaken);
        }
        let mut batch = vec![keywrap_block(cipher, &new_pk, &inner.vault_id, &kw.v)];
        batch.extend(chaff_blocks(cipher, chaff::on_commit(1)));
        let positions = append_shuffled(&heap, batch)?;
        heap.overwrite(&[(kw.block_index, cipher.chaff())])?;
        inner.keywrap_index = positions[0];
        Ok(ChangePasswordResult::Changed)
    }

    /// Arms a one-shot rule in `session`'s vault: on its next unlock the vault opened by
    /// `target_password` is shredded. Only the target's locators are stored, never its keys.
    pub fn arm_wipe(&self, session: Arc<Session>, target_password: String) -> Result<ArmWipeResult> {
        let target_password = Zeroizing::new(target_password);
        let pk = self.derive(&target_password)?;
        let heap = guard(&self.shared.heap)?;
        let mut inner = guard(&session.inner)?;
        inner.keys()?;
        let cipher = &self.shared.cipher;
        let Some(kw) = find_keywrap(&heap, cipher, &pk, &heap.read_nonces()?)? else {
            return Ok(ArmWipeResult::NotFound);
        };
        if kw.vault_id == inner.vault_id {
            return Ok(ArmWipeResult::SameVault);
        }
        let vk = VaultKeys::derive(&kw.v);
        inner.set_wipe_target(&heap, cipher, Some([&pk.loc, &vk.meta_loc, &vk.blob_loc, &vk.pad_loc]))?;
        Ok(ArmWipeResult::Armed)
    }

    pub fn disarm_wipe(&self, session: Arc<Session>) -> Result<()> {
        let heap = guard(&self.shared.heap)?;
        guard(&session.inner)?.set_wipe_target(&heap, &self.shared.cipher, None)
    }

    /// Shreds every block of the session's vault, then closes the session.
    pub fn delete_vault(&self, session: Arc<Session>) -> Result<()> {
        let heap = guard(&self.shared.heap)?;
        let mut inner = guard(&session.inner)?;
        let cipher = &self.shared.cipher;
        {
            let keys = inner.keys()?;
            shred(&heap, cipher, &keys.own_locators(), &[inner.keywrap_index])?;
        }
        inner.close(&heap, cipher)
    }

    /// Removes the session's own PAD blocks and compacts the file (spec §8.3). The file
    /// shrinks, which someone comparing two copies can see. Returns the blocks freed.
    pub fn reclaim_space(&self, session: Arc<Session>) -> Result<u32> {
        let heap = guard(&self.shared.heap)?;
        let mut inner = guard(&session.inner)?;
        inner.keys()?;
        if inner.pad_blocks.is_empty() {
            return Ok(0);
        }
        let remove = inner.pad_blocks.clone();
        let mapping = heap.compact(&remove)?;
        inner.remap(&mapping);
        Ok(remove.len() as u32)
    }

    pub fn add_background_chaff(&self) -> Result<()> {
        let heap = guard(&self.shared.heap)?;
        let n = chaff::background();
        if n > 0 {
            append_shuffled(&heap, chaff_blocks(&self.shared.cipher, n))?;
        }
        Ok(())
    }

    pub fn block_count(&self) -> Result<u32> {
        Ok(guard(&self.shared.heap)?.block_count()?)
    }
}
