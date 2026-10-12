# Container format, version 1 (draft)

Status: **draft, not audited**. Until 1.0 the format may change without migrations.
The project is called **Palimpsest** (see §13). The domain separation strings
(`plmp1-…`) do not depend on the final name and will not change.

The key words MUST / MUST NOT / SHOULD / MAY are used as in RFC 2119.
The threat model and the limits are described in [threat-model.md](threat-model.md). This
document describes only the format and the behaviour without which the format is unsafe.

---

## 1. Overview

A container is a **salt block** and a **set of blocks** of one size.
The order of the blocks carries no meaning. Every block is one of three things:

| What | Who can recognise it | What it looks like from outside |
|---|---|---|
| a vault's data block (key, entry, attachment) | only the holder of the vault key | 4096 random bytes |
| PAD, a vault's "own filler" (the trace of a physical deletion) | only the holder of the vault key | 4096 random bytes |
| chaff | nobody | 4096 random bytes |

There is always chaff in the container, even with a single vault. So "unknown blocks" are
normal for every user. All of deniability rests on this.

Opening a vault:

```
password ──Argon2id(salt)──► P ──KDF──► K_pw_loc, K_pw_wrap
                                           │
          scan the nonces of all blocks by K_pw_loc ▼
                                     KEYWRAP block ──AEAD(K_pw_wrap)──► V (vault key)
                                                                         │
                       scan nonces by K_meta_loc / K_blob_loc / K_pad_loc ▼
                                              own blocks ──AEAD(K_enc)──► operation log
```

## 2. Constants

| Name | Value | Comment |
|---|---|---|
| `BLOCK_SIZE` | 4096 | the whole block, bytes |
| `SALT_LEN` | 16 | `crypto_pwhash_SALTBYTES` |
| `R_LEN` | 16 | random part of the nonce |
| `LOC_LEN` | 8 | locator |
| `NONCE_LEN` | 24 | `R_LEN + LOC_LEN`, the XChaCha20 nonce |
| `TAG_LEN` | 16 | Poly1305 |
| `PT_LEN` | 4056 | plaintext of a block = `4096 − 24 − 16` |
| `FRAME_HDR` | 27 | frame header (§5) |
| `FRAME_CAP` | 4029 | frame payload = `PT_LEN − FRAME_HDR` |
| `ARGON_OPS` | 3 | opslimit |
| `ARGON_MEM` | 67 108 864 | memlimit, 64 MiB |
| `AAD_PREFIX` | `"plmp1-block"` (ASCII, 11 bytes) | |
| `CTX_PW` | `"plmp1-pw"` | `crypto_kdf` context, 8 bytes |
| `CTX_VK` | `"plmp1-vk"` | `crypto_kdf` context, 8 bytes |
| `MAX_OP_PARTS` | 256 | ≈1 MiB per operation |
| `MAX_BLOB_PARTS` | 65 536 | ≈252 MiB per attachment |

All integers are unsigned, little-endian. `u8/u16/u32/u64` are 1/2/4/8 bytes.

## 3. Cryptography

The format is defined through the libsodium functions below: their output is the reference.
The working core (`core-rs/`, Rust) implements them with RustCrypto crates (`argon2`,
`blake2`, `chacha20poly1305`) and MUST match libsodium byte for byte. This is checked by
the test vectors in `test-vectors/`, taken from the reference implementation on libsodium
(`core/`, Kotlin).

| Purpose | libsodium function |
|---|---|
| key from the password | `crypto_pwhash` (`ALG_ARGON2ID13`, out = 32) |
| subkey derivation | `crypto_kdf_derive_from_key` (BLAKE2b), 32-byte subkey |
| locator, nonces of parts | `crypto_generichash` (keyed BLAKE2b), out = 16 |
| block encryption | `crypto_aead_xchacha20poly1305_ietf_encrypt/decrypt` |
| randomness | `randombytes_buf` |
| comparison | `sodium_memcmp` |

### 3.1 Password

1. The string is normalised to Unicode **NFC** and encoded as UTF-8. Spaces at the ends are
   **not** trimmed: that is the interface's business, the format leaves the password as is.
2. `P = crypto_pwhash(32, password, salt, ARGON_OPS, ARGON_MEM, ARGON2ID13)`.
3. `K_pw_loc  = kdf(P, id=1, CTX_PW)`, `K_pw_wrap = kdf(P, id=2, CTX_PW)`.

Exactly one Argon2id run per unlock attempt, whatever is in the file.

### 3.2 Vault key

`V` is 32 random bytes, made together with the vault and never changed (changing the
password re-encrypts only the KEYWRAP). From `V`:

| id | Subkey | Purpose |
|---|---|---|
| 1 | `K_enc` | AEAD for all of the vault's blocks except KEYWRAP |
| 2 | `K_meta_loc` | locator of operation blocks |
| 3 | `K_blob_loc` | locator of attachment blocks |
| 4 | `K_pad_loc` | locator of PAD |
| 5 | `K_blob_nonce` | derivation of the nonces of attachment parts (§6.4) |

`kdf(V, id, CTX_VK)`.

### 3.3 Locator and nonce

```
loc(K, r) = BLAKE2b(key=K, msg=r, outlen=16)[0..8]
nonce     = r ‖ loc(K_class, r)          // 16 + 8 = 24 bytes
```

`r` is random (except for attachment parts, §6.4). For chaff all 24 bytes are random.

*Why.* Without a locator every unlock would have to try AEAD on every block with every key:
with 50,000 blocks that is 200 MiB of decryption. With the locator an unlock costs one
BLAKE2b over 16 bytes per block and class, and AEAD runs only on the vault's own blocks.
A false match on someone else's block has probability 2⁻⁶⁴, and AEAD rejects it anyway.
Without the key the locator is indistinguishable from random (the PRF property of keyed
BLAKE2b). The nonce keeps 128 random bits: collisions are negligible up to 2⁴⁸ blocks.

### 3.4 Block encryption

```
block = nonce ‖ crypto_aead_xchacha20poly1305_ietf_encrypt(
            key   = K,                 // K_pw_wrap for KEYWRAP, K_enc otherwise
            nonce = nonce,
            msg   = plaintext[PT_LEN],
            ad    = AAD_PREFIX ‖ salt)
```

The salt in the AAD ties a block to its container: a block slipped in from another container
does not decrypt. The format version sits in `AAD_PREFIX`, so it is authenticated but not
visible in the file.

## 4. Container

### 4.1 Logical model

`Container = (salt_block, Set<Block>)`, where a block's identity is its nonce (24 bytes).
Two blocks with the same nonce count as one block.

### 4.2 Salt block

The first `BLOCK_SIZE` bytes of the container: `salt[16] ‖ random[4080]`. Made once and
copied as is when syncing. The salt identifies the container: only containers with the same
salt block can be merged.

### 4.3 The file on the device (heap)

```
heap.bin = salt_block ‖ block ‖ block ‖ …          (size a multiple of 4096)
```

* New blocks are appended at the end in a batch. The order within a batch MUST be shuffled
  (Fisher–Yates on `randombytes_uniform`), so that the position does not tell chaff from data.
* The only write that is not an append is **overwriting in place** on physical deletion (§8).
* If after a crash the file size is not a multiple of 4096, the tail is cut off. A torn
  write gives an incomplete object; incomplete objects are ignored (§5.3).
* A cache `nonces.bin` MAY be kept (the nonces of all blocks in a row, 24 bytes per block).
  It is rebuilt from the heap, reveals nothing new and turns the scan into a sequential read
  of `24·N` bytes instead of `4096·N`. If it disagrees with the heap, it is rebuilt.

## 5. Block plaintext: the frame

```
offset  size  field
0       1     kind          0x01 KEYWRAP | 0x02 OP | 0x04 BLOB  (0x03 reserved)
1       16    object_id
17      4     part_index    u32
21      4     part_count    u32
25      2     data_len      u16
27      data_len  data
…       …     zeros up to PT_LEN
```

### 5.1 Parsing rules (also the fuzzing targets)

A frame MUST be rejected if:

1. `kind` is unknown or does not match the locator class
   (KEYWRAP ↔ `K_pw_loc`, OP ↔ `K_meta_loc`, BLOB ↔ `K_blob_loc`);
2. `part_count == 0`, or `part_index ≥ part_count`, or `part_count` exceeds
   `MAX_OP_PARTS` / `MAX_BLOB_PARTS`; a KEYWRAP must have `part_count` 1;
3. `data_len > FRAME_CAP`;
4. `part_index < part_count − 1` and `data_len ≠ FRAME_CAP` (every part but the last is full,
   so every object has exactly one canonical split);
5. any padding byte is not zero.

A rejected frame counts as someone else's block: it is neither touched nor deleted.

### 5.2 Splitting

An object of length `L` is cut into `max(1, ceil(L / FRAME_CAP))` consecutive parts.
`object_id` is 16 random bytes; for attachments it is the `seed` (§6.4).

### 5.3 Assembly

An object is assembled when all `part_index ∈ [0, part_count)` are present with the same
`kind`/`part_count`. Two different parts with the same `(object_id, part_index)` mean
corruption: the object is marked broken, its blocks are kept. An incomplete object is not
applied and not deleted: the missing parts may arrive with a sync.

## 6. Objects

### 6.1 KEYWRAP (one part, key `K_pw_wrap`)

```
vault_id[16] ‖ V[32] ‖ created_hlc u64
```

The frame's `object_id` is the wrap's identifier (`keywrap_id`).

### 6.2 OP, a log operation (key `K_enc`)

```
op_ver u8 (=1) ‖ op_type u8 ‖ hlc u64 ‖ session_id[16] ‖ body (TLV, §6.3)
```

`op_id` = the frame's `object_id`.

| op_type | Name | Body |
|---|---|---|
| 0x01 | `PUT` | a revision of an entry: the entry's fields + `supersedes` |
| 0x02 | `DELETE` | `entry_id`, `supersedes` (a tombstone) |
| 0x03 | `REVOKE_KEYWRAP` | `keywrap_id` |

Vault settings are an entry too, with `entry_id = 00…00` and `entry_type = settings`.
So they sync and resolve conflicts like ordinary entries.

**HLC** (`u64`) = `(unix_ms << 16) | counter`. A new value is > max(all HLCs in the vault,
the current time). It is used for sorting and for picking the default version; merge
correctness does not depend on it.

**session_id** is 16 random bytes per session (unlock → lock). There is no permanent device
identifier: it would have to be stored outside the vaults, and that would reveal how many
vaults exist.

### 6.3 TLV

```
tag u8 ‖ len (LEB128, ≤ 4 bytes, ≤ 2²⁰) ‖ value[len]
```

| tag | Field | Type | Where |
|---|---|---|---|
| 0x01 | `entry_id` | 16 bytes | PUT, DELETE |
| 0x02 | `supersedes` | n × 16 bytes (op_id), sorted, no repeats | PUT, DELETE |
| 0x03 | `entry_type` | u8: 1 diary, 2 note, 3 to-do, 4 folder (`title` is its name), 0xFF settings | PUT |
| 0x04 | `created_ms` | u64 | PUT |
| 0x05 | `diary_date` | u32, days since 1970-01-01 | PUT |
| 0x06 | `title` | UTF-8 | PUT |
| 0x07 | `body` | UTF-8 (Markdown) | PUT |
| 0x08 | `tag` | UTF-8, repeatable | PUT |
| 0x09 | `attachment` | nested TLV (§6.4) | PUT, repeatable |
| 0x0A | `keywrap_id` | 16 bytes | REVOKE_KEYWRAP |
| 0x10 | `done` | u8 = 1; absent = not done | PUT of a to-do |
| 0x11 | `folder` | 16 bytes: the folder's `entry_id` | PUT |
| 0x12 | `color` | u8 1..=8, palette index; absent = no colour | PUT |
| 0x13 | `pinned` | u8 = 1 | PUT |
| 0x14 | `trashed` | u64, unix ms when moved to the trash. After 30 days the entry is deleted (DELETE) at unlock | PUT |
| 0x15 | `order` | u32 LE, a folder's place in the folder row; absent = after the ordered ones, by name | PUT of a folder |
| 0x40–0x7F | settings (§10) | | settings PUT |

Unknown tags MUST be kept as they are when an entry is rewritten (forward compatibility).
A repeated non-repeatable tag, a `len` beyond the buffer or invalid UTF-8 mean the whole
operation is rejected.

Bodies are Markdown. A photo placed in the text is a line of its own,
`![<left|center|right> <s|m|l>](att:<seed>)`; when it is small or medium and on a side, the
lines right after it, up to the first blank line, are the text beside it. A link to another
entry is `[text](note:<entry_id>)`. Lines starting with `:-: ` and `--: ` are centred and
right-aligned.

### 6.4 BLOB, an attachment (key `K_enc`)

An attachment is an immutable object: `object_id = seed` (16 random bytes),
`part_count = n`. The nonce of part `i` is **computed**, not stored:

```
r_i     = BLAKE2b(key=K_blob_nonce, msg = seed ‖ u32(i), outlen=16)
nonce_i = r_i ‖ loc(K_blob_loc, r_i)
```

The reference in a PUT (`attachment`, tag 0x09, repeatable, nested TLV):

| tag | Field | Type |
|---|---|---|
| 0x01 | `seed` | 16 bytes |
| 0x02 | `parts` | u32 |
| 0x03 | `size` | u64, bytes |
| 0x04 | `mime` | UTF-8 (`image/jpeg`, `audio/aac`) |
| 0x05 | `thumb_seed` | 16 bytes, optional |
| 0x06 | `thumb_parts` | u32, together with 0x05 |
| 0x07 | `duration_ms` | u64, for audio |
| 0x08 / 0x09 | `width` / `height` | u32, for images |

A BLOB that no operation in memory refers to is dead and is overwritten as in §8.2. In
`manual` mode it waits for "Erase now" while a replaced revision refers to it. The app's
limit is 25 MiB per attachment.

To open an attachment, the nonces of its parts are looked up in the index. Only those are
decrypted: BLOB blocks are not touched at unlock.
A `seed` MUST NOT be used for two different contents (that is a nonce reuse).
A changed attachment gets a new seed.

### 6.5 PAD

```
PAD = r ‖ loc(K_pad_loc, r) ‖ random[4072]
```

PAD is never decrypted: a vault recognises its own PAD by the locator. It is space the vault
may free later (§8.3).

## 7. Data model and merging

### 7.1 Revisions of an entry

Every PUT/DELETE carries `supersedes`: the **full** set of op_ids of all its ancestors (the
revisions this operation replaces), not only the parent:

```
new.supersedes = ⋃(h.supersedes ∪ {h.op_id}) over every head the editor saw
```

* An entry's **head** is an operation whose op_id is in the `supersedes` of no other known
  operation of that entry.
* One PUT head is the normal state. A DELETE head with no PUT heads means the entry is deleted.
* Several PUT heads are a **conflict** (concurrent edits). The head with the largest
  `(hlc, session_id)` is shown, with a note "there is another version". A conflict is
  resolved by a new PUT that replaces both.
* A PUT concurrent with a DELETE keeps the entry (add wins): no edit is lost.

The full set of ancestors costs 16 bytes per edit. For a diary, where an entry is edited a
handful of times, that is nothing. In return the state does not depend on the order of
delivery, on repeats or on intermediate revisions having been physically erased already. An
old revision coming back from another device is recognised as replaced at once.

### 7.2 Vault state

`State = f(set of assembled OPs)`. f is commutative, associative and idempotent, so merging
two copies of a container gives the same state on every device (a CRDT: a multi-value
register per entry). There are no checkpoints in v1: `kind 0x03` is reserved for an index,
should unlock speed need one (§11).

### 7.3 Sync

`merge(A, B) = (salt, A.blocks ∪ B.blocks)` for equal salt blocks. Merging works **without a
password** and without knowing how many vaults the container holds.

The transport (a folder, WebDAV, a server) carries blocks and the salt block. Segmenting and
the protocol are described separately. Requirements for a transport:

* carry only whole blocks;
* never require the core to tell blocks apart (the server does not know whose a block is);
* the transport's settings are kept **inside the vault** (a settings PUT), and syncing runs
  only while that vault is open (§10).

## 8. Deletion

### 8.1 Dead objects

While a vault is open, an object of it is dead if it is:

* a PUT whose op_id is in somebody's `supersedes`;
* a BLOB that no live PUT head refers to;
* a KEYWRAP revoked by a `REVOKE_KEYWRAP` operation.

DELETE and REVOKE_KEYWRAP **never die**. They are tombstones: they hold no content, and
losing them would bring a deleted entry back or give an old password its power back.

### 8.2 Physical deletion (purge)

Every block of a dead object is overwritten **in place**: a fresh PAD of the same vault is
written instead. The file size does not change.

* Blocks made **in the current session** and not yet synced anywhere (autosaved drafts, for
  instance) are always overwritten at once. No snapshot of the file has had them yet, so
  overwriting them gives nothing away.
* Blocks from earlier sessions are overwritten according to the vault setting `purge_mode`:
  `immediate` (the default) or `manual`. Overwriting an old unknown block is visible to an
  adversary with two snapshots of the file and the password of another vault (see the threat
  model, R4). That is why this is a setting, not a fixed rule.
* A dead block that arrives with a sync while the vault is open is not written to the heap
  at all.

This is a **deliberate departure** from "append only". The logical model stays a growing set
with union on merge. Overwriting is a local operation and does not break convergence: a
block brought back by a sync is recognised as dead again and overwritten again.

A limit: on flash memory, overwriting a page does not guarantee that the old data is
physically gone (wear levelling). This does not protect against a forensic lab, and the
threat model says so.

### 8.3 Reclaiming space (by hand)

"Reclaim space" in an open vault removes its PAD blocks and compacts the heap. The file
shrinks, and that shows when snapshots are compared. So it is only by hand, with a warning.
Chaff is never removed: it cannot be told apart from other vaults.

## 9. Chaff

Chaff is a block of 4096 bytes from `randombytes_buf`. It is added in the same way whatever
vault is open and whether any is:

| When | How many blocks |
|---|---|
| container created | `16 + uniform(0, 48)` |
| every commit (a write batch) | `G + ceil(U · w)` |
| session end, even without writes | `G` |
| a background job once a day (WorkManager), with probability ½ | `G` |
| with probability 1/100 on any of the above | another `uniform(16, 128)` |

`G ~ Geometric(½)` on {0, 1, 2, …} (mean 1), `U ~ Uniform[0,1)`, `w` = the number of
non-chaff blocks in the batch. Chaff is shuffled together with the batch's data.

The `U·w` term makes the growth of unknown blocks correlate with the amount written to any
vault. Then a large write to a hidden vault looks like a large write to the decoy plus chaff.

These are **starting** parameters, to be tuned by simulating a distinguisher (§12, T5).
Cost estimate: 3 sessions a day give ≈ 2–3 blocks per session, that is ≈ 10–12 MiB a year
plus `w/2` of the amount written.

## 10. Vault settings (settings PUT, tags 0x40–0x7F)

| tag | Setting | Default |
|---|---|---|
| 0x40 | reserved (was `duress_wipe`, replaced by 0x45) | — |
| 0x41 | `purge_mode`: immediate / manual | immediate |
| 0x42 | `sync`: transport and credentials (nested TLV) | none |
| 0x43 | `autolock_s` | 60 |
| 0x44 | `biometric`: whether biometric unlock is allowed | off |
| 0x45 | `wipe_target`: locators of the vault to destroy at the next unlock (§10.1) | none |

The settings are an entry with `entry_id = 00…00` and `entry_type = 0xFF`; it is not shown in
the list of entries.

**Outside the vaults** (in the app's ordinary settings) anything that depends on the number of
vaults or their properties MUST NOT be kept. Allowed: the language, the theme of the unlock
screen, a counter of failed attempts, one biometric wrap (§10.2).

### 10.1 wipe_target: "on opening, destroy another vault"

A one-shot rule in the settings of vault D. It is set from an open D by typing the password of
another vault R (D's own password is refused):

```
wipe_target = K_pw_loc(R)[32] ‖ K_meta_loc(R)[32] ‖ K_blob_loc(R)[32] ‖ K_pad_loc(R)[32]
```

At the next unlock of D, whoever does it:
1. Every block matching any of R's four locators (KEYWRAP, OP, BLOB, PAD) is overwritten
   **in place** with random bytes. The file size does not change, the overwritten blocks are
   indistinguishable from chaff.
2. A new revision of the settings without `wipe_target` is written; the old revision is
   overwritten as in §8.2. Whoever opens D's settings after the unlock does not see the rule.
3. D is shown as usual. D's password keeps working.

The rule holds **neither** `V(R)` nor `K_enc(R)`: someone who learned D's password and holds an
earlier copy of the file finds R's blocks but cannot read them. The rule can be removed
without firing only in the same session of D in which it was set: a closed D cannot be checked
without unlocking it.

A limit: the rule keeps the `K_pw_loc` of R's password at the time it was set. If R's password
is changed later, its new KEYWRAP survives and opens an empty vault.

If D has both `wipe_target` and `sync`, the interface MUST warn: the next sync brings
everything wiped back from the remote copy.

### 10.3 Deleting a vault

From the open vault: overwrite in place every block matching its `K_meta_loc`, `K_blob_loc`,
`K_pad_loc`, and its KEYWRAP block, then close the session.

### 10.2 Biometrics (planned)

An Android Keystore key (`BIOMETRIC_STRONG`, `setInvalidatedByBiometricEnrollment(true)`)
wraps the `V` of **exactly one** vault. The wrap lies outside the container, and its presence
says only "biometrics is on". The app does not know which vault is the decoy, so the technical
rule is "at most one vault"; the interface strongly suggests the decoy. Turning biometrics on
for another vault erases the previous wrap.

## 11. Procedures

### 11.1 Creating a container

The salt block and the initial chaff (§9). No vaults.

### 11.2 Creating a vault

1. Derive `P` from the new password and scan the container. If a KEYWRAP is found, answer
   "choose another password": two vaults with one password are impossible.
2. `V`, `vault_id` random. A batch: KEYWRAP, settings PUT, chaff. Shuffle, append, `fsync`.

### 11.3 Unlocking

1. Read the salt, derive `P`, `K_pw_loc`, `K_pw_wrap`.
2. Load the nonces of all blocks (the cache or the heap).
3. For each nonce check `loc(K_pw_loc, r)`. Decrypt the candidates as KEYWRAP.
4. No valid KEYWRAP → "Wrong password". There is no other possible answer.
5. Derive `V`'s subkeys; classify all nonces by `K_meta_loc`, `K_blob_loc`, `K_pad_loc`.
6. Decrypt the OP blocks, assemble the objects, compute the state.
7. If the KEYWRAP used is revoked → "Wrong password", close the vault.
8. Purge as in §8.2 (with chaff if anything was overwritten).

### 11.4 Changing the password

A new KEYWRAP with the same `V` + `REVOKE_KEYWRAP` of the old one + purge of the old KEYWRAP.
The old password stops working on this container but **keeps opening old copies of the file**
(backups, other devices before a sync). This is unavoidable, and the interface says so.

### 11.5 Estimated unlock speed

The estimates below are to be confirmed by a benchmark (T6). A weak phone: Cortex-A53, eMMC.

| Step | 10,000 blocks (40 MiB) | 50,000 blocks (200 MiB) |
|---|---|---|
| Argon2id 64 MiB × 3 | 0.7–2 s | 0.7–2 s |
| reading `nonces.bin` | 240 KB, ≈0 | 1.2 MB, < 0.05 s |
| 4 BLAKE2b per block | ≈ 0.02 s | ≈ 0.1 s |
| AEAD of own OP blocks (≈ 5,000 = 20 MiB) | 0.2–0.4 s | 0.2–0.4 s |

Attachments do not affect unlocking: their blocks are only classified by locator. If text OP
blocks grow beyond ~20,000, add `kind 0x03`, an encrypted index (a snapshot of the state with
references to live PUTs), format v1.1.

### 11.6 Argon2id parameters

The parameters are **constants of the format**, not a field in the file: a readable field
would be a signature, and without such a field parameters cannot be tuned per device.

* 64 MiB / t=3 / p=1. libsodium does not support p>1, and the memory fits the limits of
  Android Go with 1–2 GB of RAM. This is `MEMLIMIT_INTERACTIVE` with one more pass, above the
  OWASP minimums (19 MiB / t=2, 46 MiB / t=1).
* Acceptance criterion before v1 is frozen: ≤ 2 s on the reference weak device (Cortex-A53).
  If it fails, 46 MiB / t=2. The decision is fixed before 1.0 and not changed afterwards.
* Changing the parameters after 1.0 means a new format version and a migration that
  re-encrypts the KEYWRAPs: the user types every password once, one at a time.

### 11.7 Export and import

A file in either direction is an ordinary container of this same format. There is no separate
export format: it would have a signature of its own.

* **A copy of everything** is a byte-for-byte copy of the container under the heap lock: every
  vault, all the chaff, the same salt. Like the file itself, it does not show how many vaults
  it holds. **Restoring** replaces the container with the copy whole (only the shape is
  checked: a length that is a multiple of 4096 and at least two blocks); `nonces.bin` is
  removed. Copies are not merged: that would bring back wiped and deleted vaults.
* **Exporting a vault** makes a new container (a new salt, its own initial chaff) holding one
  vault with a **new** `V` under the export password. The head PUTs of all entries and
  folders are carried over with the same `entry_id`, `hlc`, `created_ms`, date, tags and
  attributes. History (replaced revisions), tombstones and the settings PUT (purge_mode,
  wipe_target) are not carried over. The new `V` keeps the export password from opening the
  vault on the phone and keeps the two files' locators from matching.
* **Attachments** are re-encrypted: the new seed =
  `BLAKE2b-128(K_blob_nonce of the receiver, "plmp1-import" ‖ seed of the source)`. Importing
  the same attachment into the same vault again gives the same seed and writes nothing; for
  different vaults the seeds differ. The `att:<seed>` references in an entry's text are
  replaced.
* **Importing a vault** (from a copy or an export): the password is looked up in the file; if
  the same password opens a vault here, the entries are merged into it, otherwise a new vault
  with this password is made. An entry is taken if it is not here or its `hlc` is greater
  than the latest local operation on it (deletion included). A taken entry becomes a new
  revision that replaces every local one (`supersedes`), with the source's `hlc`. Deletions
  in the file are not carried over. wipe_target rules do not fire on import.

Files on their way in and out lie in the app's temporary folder only in encrypted form and are
deleted after the transfer (and at the next start, if a transfer was interrupted).

## 12. Test plan

| # | Test |
|---|---|
| T1 | Test vectors: a deterministic RNG → `P`, subkeys, locator, KEYWRAP/OP/BLOB/PAD blocks byte for byte (JSON in `test-vectors/`) |
| T2 | Round trips: splitting/assembly, TLV, every rule of §5.1 and §6.3 on rejection |
| T3 | Fuzzing: frame parsing, TLV, object assembly, a heap with a garbage tail. Goal: no unhandled error |
| T4 | Indistinguishability of one snapshot: a container with 1 vault against 2; on the blocks, χ², byte frequencies, monobit/runs (NIST STS-lite), size distributions; they must match |
| T5 | Simulation of a two-snapshot distinguisher: the adversary knows the decoy password and sees Δ of unknown blocks; accuracy of a "hidden / none" classifier on a model of user behaviour. Tuning of the chaff parameters in §9 |
| T6 | Benchmark: Argon2id, scan, unlock with 10³…10⁵ blocks |
| T7 | Convergence: random edit scenarios on N devices + merges in any order give the same state; resurrection after purge; a conflict resolved by an edit |
| T8 | Crash safety: a write cut at any byte, a torn block during an overwrite |

## 13. Name

**Palimpsest**: a manuscript where old text hides under new text; exactly a false bottom.

## 14. Decisions on open questions (summary)

| Question | Decision | Where |
|---|---|---|
| Block size | 4096: a flash page; an entry of 1–2 KB wastes 50–75% of a block against 88–94% with 16 KiB; one size for everything, otherwise a size class gives attachments away | §2 |
| Long entries and attachments | Objects are cut into frames; attachments are a locator class of their own, part nonces are derived from the seed, not decrypted at unlock | §5, §6.4 |
| Editing | A new PUT revision with the full set of ancestors; a CRDT without coordination | §7 |
| Deletion | A DELETE tombstone forever + overwriting dead revisions in place (PAD). Other vaults' blocks are never compacted | §8 |
| Whose blocks when compacting | Each vault touches only its own; chaff and the rest are off limits | §8.3 |
| Speed of trial decryption | A locator in the nonce: BLAKE2b instead of AEAD on other blocks + a nonce cache | §3.3, §11.5 |
| Argon2id on weak phones | Constants 64 MiB / t=3, criterion ≤ 2 s, fallback 46 MiB / t=2 | §11.6 |
| Name | Palimpsest | §13 |
