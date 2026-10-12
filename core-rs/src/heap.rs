//! On-device container: salt block followed by blocks (spec §4.3).
//! Block index i lives at offset (i + 1) · BLOCK_SIZE.
//!
//! `<name>.nonces` caches every block's nonce (24 bytes each), so unlock reads 24·N bytes
//! instead of the whole heap. It is derivable from the heap, reveals nothing new and is
//! rebuilt whenever its length disagrees. The heap is always written first: a crash in
//! between leaves the cache short (rebuilt) or stale for an overwritten block, which only
//! costs one failed AEAD attempt.

use crate::format::*;
use std::collections::BTreeSet;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

pub struct Heap {
    path: PathBuf,
    cache: PathBuf,
}

fn sibling(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.file_name().unwrap_or_default().to_os_string();
    name.push(suffix);
    path.with_file_name(name)
}

impl Heap {
    pub fn new(path: impl Into<PathBuf>) -> Heap {
        let path = path.into();
        Heap { cache: sibling(&path, ".nonces"), path }
    }

    pub fn exists(&self) -> bool {
        fs::metadata(&self.path).map(|m| m.len() >= BLOCK_SIZE as u64).unwrap_or(false)
    }

    pub fn create(&self, salt_block: &[u8]) -> io::Result<()> {
        assert_eq!(salt_block.len(), BLOCK_SIZE);
        let _ = fs::remove_file(&self.cache);
        let tmp = sibling(&self.path, ".tmp");
        let mut f = File::create(&tmp)?;
        f.write_all(salt_block)?;
        f.sync_all()?;
        drop(f);
        fs::rename(&tmp, &self.path)
    }

    /// Drops a torn trailing block left by an interrupted append.
    pub fn repair_tail(&self) -> io::Result<()> {
        let len = fs::metadata(&self.path)?.len();
        let whole = len - len % BLOCK_SIZE as u64;
        if whole != len {
            OpenOptions::new().write(true).open(&self.path)?.set_len(whole)?;
            let _ = fs::remove_file(&self.cache);
        }
        Ok(())
    }

    pub fn block_count(&self) -> io::Result<u32> {
        Ok((fs::metadata(&self.path)?.len() / BLOCK_SIZE as u64).saturating_sub(1) as u32)
    }

    pub fn read_salt(&self) -> io::Result<[u8; SALT_LEN]> {
        let mut salt = [0u8; SALT_LEN];
        File::open(&self.path)?.read_exact(&mut salt)?;
        Ok(salt)
    }

    pub fn read_nonces(&self) -> io::Result<Vec<Nonce>> {
        let n = self.block_count()?;
        if !self.cache_valid(n) {
            self.rebuild_cache(n)?;
        }
        let bytes = fs::read(&self.cache)?;
        Ok(bytes.chunks_exact(NONCE_LEN).map(|c| c.try_into().unwrap()).collect())
    }

    pub fn read_block(&self, i: u32) -> io::Result<Vec<u8>> {
        let mut f = File::open(&self.path)?;
        Self::read_at(&mut f, i)
    }

    pub fn read_blocks(&self, indices: &[u32], mut consumer: impl FnMut(u32, Vec<u8>)) -> io::Result<()> {
        let mut sorted = indices.to_vec();
        sorted.sort_unstable();
        let mut f = File::open(&self.path)?;
        for i in sorted {
            consumer(i, Self::read_at(&mut f, i)?);
        }
        Ok(())
    }

    /// Appends and fsyncs; returns the index of the first appended block.
    pub fn append(&self, blocks: &[Vec<u8>]) -> io::Result<u32> {
        let base = self.block_count()?;
        let cache_was_valid = self.cache_valid(base);
        let mut f = OpenOptions::new().append(true).open(&self.path)?;
        for b in blocks {
            assert_eq!(b.len(), BLOCK_SIZE);
            f.write_all(b)?;
        }
        f.sync_all()?;
        if cache_was_valid {
            let mut c = OpenOptions::new().append(true).open(&self.cache)?;
            for b in blocks {
                c.write_all(&b[..NONCE_LEN])?;
            }
        }
        Ok(base)
    }

    /// In-place overwrite, used only for purge and shredding (spec §8.2, §10).
    pub fn overwrite(&self, writes: &[(u32, Vec<u8>)]) -> io::Result<()> {
        if writes.is_empty() {
            return Ok(());
        }
        let n = self.block_count()?;
        let mut f = OpenOptions::new().write(true).open(&self.path)?;
        for (i, b) in writes {
            assert!(b.len() == BLOCK_SIZE && *i < n);
            f.seek(SeekFrom::Start(Self::offset(*i)))?;
            f.write_all(b)?;
        }
        f.sync_all()?;
        if self.cache_valid(n) {
            let mut c = OpenOptions::new().write(true).open(&self.cache)?;
            for (i, b) in writes {
                c.seek(SeekFrom::Start(*i as u64 * NONCE_LEN as u64))?;
                c.write_all(&b[..NONCE_LEN])?;
            }
        }
        Ok(())
    }

    /// Rewrites the heap without the blocks in [remove] (spec §8.3). Returns, for every old
    /// index, its new index or None if it was removed.
    pub fn compact(&self, remove: &BTreeSet<u32>) -> io::Result<Vec<Option<u32>>> {
        let n = self.block_count()?;
        let tmp = sibling(&self.path, ".tmp");
        let mut mapping = Vec::with_capacity(n as usize);
        let mut nonces = Vec::with_capacity(n as usize * NONCE_LEN);
        {
            let mut src = BufReader::with_capacity(1 << 16, File::open(&self.path)?);
            let mut dst = BufWriter::with_capacity(1 << 16, File::create(&tmp)?);
            let mut block = vec![0u8; BLOCK_SIZE];
            src.read_exact(&mut block)?;
            dst.write_all(&block)?;
            let mut next = 0u32;
            for i in 0..n {
                src.read_exact(&mut block)?;
                if remove.contains(&i) {
                    mapping.push(None);
                } else {
                    dst.write_all(&block)?;
                    nonces.extend_from_slice(&block[..NONCE_LEN]);
                    mapping.push(Some(next));
                    next += 1;
                }
            }
            dst.into_inner().map_err(|e| e.into_error())?.sync_all()?;
        }
        let _ = fs::remove_file(&self.cache);
        fs::rename(&tmp, &self.path)?;
        let cache_tmp = sibling(&self.cache, ".tmp");
        fs::write(&cache_tmp, &nonces)?;
        fs::rename(&cache_tmp, &self.cache)?;
        Ok(mapping)
    }

    /// A consistent copy of the heap (the caller holds the container lock).
    pub fn copy_to(&self, dest: &Path) -> io::Result<()> {
        fs::copy(&self.path, dest)?;
        OpenOptions::new().write(true).open(dest)?.sync_all()
    }

    /// Atomically replaces the heap with the file at `source`; the nonce cache is dropped.
    pub fn replace_with(&self, source: &Path) -> io::Result<()> {
        let tmp = sibling(&self.path, ".tmp");
        fs::copy(source, &tmp)?;
        OpenOptions::new().write(true).open(&tmp)?.sync_all()?;
        let _ = fs::remove_file(&self.cache);
        fs::rename(&tmp, &self.path)
    }

    fn cache_valid(&self, n: u32) -> bool {
        fs::metadata(&self.cache).map(|m| m.len() == n as u64 * NONCE_LEN as u64).unwrap_or(false)
    }

    fn rebuild_cache(&self, n: u32) -> io::Result<()> {
        let tmp = sibling(&self.cache, ".tmp");
        let mut src = BufReader::with_capacity(1 << 16, File::open(&self.path)?);
        let mut out = Vec::with_capacity(n as usize * NONCE_LEN);
        let mut block = vec![0u8; BLOCK_SIZE];
        src.read_exact(&mut block)?;
        for _ in 0..n {
            src.read_exact(&mut block)?;
            out.extend_from_slice(&block[..NONCE_LEN]);
        }
        fs::write(&tmp, &out)?;
        fs::rename(&tmp, &self.cache)
    }

    fn read_at(f: &mut File, i: u32) -> io::Result<Vec<u8>> {
        f.seek(SeekFrom::Start(Self::offset(i)))?;
        let mut b = vec![0u8; BLOCK_SIZE];
        f.read_exact(&mut b)?;
        Ok(b)
    }

    fn offset(i: u32) -> u64 {
        (i as u64 + 1) * BLOCK_SIZE as u64
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::random_bytes;

    fn blocks(n: usize) -> Vec<Vec<u8>> {
        (0..n).map(|_| random_bytes(BLOCK_SIZE)).collect()
    }

    fn scanned(h: &Heap) -> Vec<Nonce> {
        (0..h.block_count().unwrap()).map(|i| h.read_block(i).unwrap()[..NONCE_LEN].try_into().unwrap()).collect()
    }

    #[test]
    fn cache_follows_writes_and_is_rebuilt() {
        let dir = tempfile::tempdir().unwrap();
        let h = Heap::new(dir.path().join("data.bin"));
        h.create(&random_bytes(BLOCK_SIZE)).unwrap();
        h.append(&blocks(5)).unwrap();
        assert_eq!(h.read_nonces().unwrap(), scanned(&h));
        h.append(&blocks(3)).unwrap();
        h.overwrite(&[(1, random_bytes(BLOCK_SIZE)), (6, random_bytes(BLOCK_SIZE))]).unwrap();
        assert_eq!(h.read_nonces().unwrap(), scanned(&h));

        fs::remove_file(dir.path().join("data.bin.nonces")).unwrap();
        assert_eq!(h.read_nonces().unwrap(), scanned(&h));
    }

    #[test]
    fn compact_removes_and_maps() {
        let dir = tempfile::tempdir().unwrap();
        let h = Heap::new(dir.path().join("data.bin"));
        h.create(&random_bytes(BLOCK_SIZE)).unwrap();
        let b = blocks(5);
        h.append(&b).unwrap();
        let map = h.compact(&[1, 3].into()).unwrap();
        assert_eq!(map, vec![Some(0), None, Some(1), None, Some(2)]);
        assert_eq!(h.block_count().unwrap(), 3);
        assert_eq!(h.read_block(2).unwrap(), b[4]);
        assert_eq!(h.read_nonces().unwrap(), scanned(&h));
    }

    #[test]
    fn torn_tail_is_repaired() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("data.bin");
        let h = Heap::new(&path);
        h.create(&random_bytes(BLOCK_SIZE)).unwrap();
        h.append(&blocks(2)).unwrap();
        OpenOptions::new().append(true).open(&path).unwrap().write_all(&[0; 10]).unwrap();
        h.repair_tail().unwrap();
        assert_eq!(fs::metadata(&path).unwrap().len() % BLOCK_SIZE as u64, 0);
        assert_eq!(h.read_nonces().unwrap(), scanned(&h));
    }
}
