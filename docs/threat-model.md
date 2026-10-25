# Threat model (draft)

Status: **draft. Neither the code nor the format has had an independent audit.**
The format is described in [format-spec.md](format-spec.md).

## 1. What we protect

| Asset | Example |
|---|---|
| S1. The content of entries | texts, titles, tags, attachments, dates |
| S2. The existence of a particular vault | "is there anything besides what I showed" |
| S3. The number of vaults | |
| S4. Passwords | |

**Not protected:** that the app is installed, that a diary is kept, the total amount of
data (the file size), times of activity.

## 2. Adversaries

| | Who | What they can do | What they cannot |
|---|---|---|---|
| **A1** | A partner, someone close | Access to the phone, locked or not, briefly and many times; may know the PIN; looks over the shoulder; may demand the app's password | root, pulling files out of the app's private storage, special software |
| **A2** | A thief | The whole phone for a long time; if it is unlocked, a logical extraction with ordinary tools; offline guessing | make you type anything |
| **A3** | A border check | Demands to unlock the phone and the app; looks for a limited time; may take a copy of the data with a commercial kit (Cellebrite class), also on several trips; offline guessing | long lab analysis, chip-off, analysis of the running device's memory, OS exploits, holding you for weeks |
| **A4** | A sync provider (WebDAV, own server) or anyone with access to the cloud | Sees every version of the file over time, so many snapshots; sizes and times | passwords, unless A1–A3 got them |

Dangerous combinations: **A3 on two trips** or **A4 together with the decoy password**.
That is an adversary with two snapshots of the container and one password (§4, R3–R4).

## 3. Out of scope

We say plainly that the app does **not** protect against:

* a forensic lab: chip-off, recovery of erased flash pages, cold boot, analysis of a RAM dump;
* malware, stalkerware, a compromised OS or firmware, root in the adversary's hands before
  you type the password;
* a third-party keyboard that ignores the incognito flag or sends input to the network;
  accessibility services that read the screen;
* a camera or someone watching over the shoulder while the password is typed;
* coercion that does not stop after one password is given (R1);
* legal compulsion and liability for refusing to give a password or for destroying data;
* a tampered build (partly covered by F-Droid's reproducible builds).

## 4. Goals and how they are met

### G1. Confidentiality (S1)

Without the password the content cannot be read. XChaCha20-Poly1305 with the vault key; the
vault key is wrapped by a key from Argon2id(password). **Against:** A1–A4.

### G2. Deniability from one snapshot (S2, S3)

The adversary has the file and **any** subset of the passwords. They cannot tell "there is
another vault" from "there is nothing else" better than by guessing. This rests on:

* every block is 4096 bytes indistinguishable from random (a nonce with a random part and a
  PRF locator, the AEAD ciphertext, padding inside the ciphertext);
* no signatures, headers or version in clear;
* chaff is **always** added: when the container is created, in every session and in the
  background. Every user has unknown blocks, so by themselves they are no evidence;
* the app behaves the same whatever vault is open: it does not know which one is the decoy;
* nothing outside the vaults depends on how many there are.

**Against:** A1–A3 with one snapshot.

### G3. Limited deniability from several snapshots

The adversary compares two snapshots and knows the decoy password. Then:

* the **appearance** of unknown blocks is covered by chaff. The protection is statistical,
  not absolute: its quality is measured by a simulation (T5);
* the **change or disappearance** of old unknown blocks is **not covered** (R4).

### G4. Integrity of single entries

A block cannot be forged or swapped without the key (AEAD, the salt in the AAD). Removing
blocks and rolling them back are **not detected** (R7).

### G5. Cost of guessing (S4)

One Argon2id run (64 MiB, t=3) per attempt. This slows guessing down but **does not save a
weak password**.

On top of it the app waits a fixed 1 second before every answer that tells whether a password
opens a vault: a wrong password at unlock, "password taken" when creating a vault and when
changing a password, a wrong current password, "not found" in the destroy rule and on import.
Otherwise creating a vault would be the same oracle as unlocking, only without the pause.
Against a copy of the file the pause does not help: there only Argon2id counts.

### G6. Crafted vaults do not crash the app

Encryption keeps strangers from writing readable blocks, but not everyone who writes them is
a stranger. An exported vault arrives with its password, so whoever made the file chose every
decrypted byte the import will parse; anyone who knows a decoy password can put such blocks
into the decoy. A parser that panics or allocates without bound on them would lock the owner
out of their own diary.

So the decoders (TLV, operations, frames, attachment references) are total: anything
malformed is treated as foreign, never as an error that stops the unlock. This is checked by
fuzzing ([`core-rs/fuzz`](../core-rs/fuzz), harness in
[`src/fuzzing.rs`](../core-rs/src/fuzzing.rs)): libFuzzer feeds each decoder arbitrary bytes
and checks that whatever decodes writes back the same way, and one target seals fuzzed
plaintexts into real blocks of a vault, loads it, reads every entry and attachment and imports
it into another vault. It runs a minute per target on every push and 25 minutes a day in CI.
The key derivation is skipped there: the target starts from the vault key, since Argon2id
would leave only a few runs per second.

## 5. Residual risks

| # | Risk | Who it concerns | What we do |
|---|---|---|---|
| R1 | **Coercion goes on.** The adversary knows the app can have hidden vaults and demands "the second password". Proving there is none is impossible, and that is true for people who really have none, too | A1, A3 | Said honestly in the README. Chaff for every user makes "I have one vault" plausible, but not provable |
| R2 | **A weak password** is guessed offline from a copy | A2, A3, A4 | A password strength meter; a phrase of 5–6 words is recommended for the real vault. The decoy's password can be simple: its opening is expected |
| R3 | **Growth of unknown blocks** between snapshots does not look like chaff: large attachments in a hidden vault, frequent sessions with a "dead" decoy | A3×2, A4 | Measured by the T5 simulation ([t5-simulation.md](t5-simulation.md)): with 1 session a day in the hidden vault and snapshots 30 days apart, AUC 0.90 with the current chaff. Large rare bursts of chaff bring it down to about 0.72 at 23 MB/year. Advice: use the decoy more actively than the hidden vault |
| R4 | **Changes to old unknown blocks** give a hidden vault away to someone with two snapshots and the decoy password: chaff never changes. Causes: purging old revisions, "Reclaim space", a duress wipe, changing the hidden vault's password | A3×2, A4 | The "erase manually" mode (`purge_mode = manual`) in the vault settings removes this channel entirely (T5: 97% → 0% within 30 days); drafts of the current session are erased without a trace. Not covered: changing the password, "Reclaim space", destroying a vault |
| R5 | **A "dead" decoy.** A year without entries while the file grows and its modification time is fresh looks suspicious | A1, A3 | Advice in the app to really use the decoy; no reminders, because a notification is evidence by itself |
| R6 | **Old copies and old passwords.** A backup or a copy on another device opens with an old password and holds revisions not yet erased | all | A warning when the password is changed; system backup is disabled. Planned: sync spreads the revocation of the old key |
| R7 | **Removing and rolling back blocks.** Someone with access to the file or a server can drop blocks, and the latest revision "rolls back" to an earlier one | A4 | Not covered in v1. A possible fix is a signed revision counter in the vault |
| R8 | **Secrets in memory.** While a vault is open, keys and decrypted text are in RAM | out of scope (RAM dump) | The core in Rust: keys, the decrypted log and every entry live only there and are zeroed (`zeroize`) on lock. The JVM gets only titles and first lines for the list and the full text of **one** open entry. **Not covered:** the password in its field and the text in the editor are JVM strings and cannot be wiped; Rust memory is not protected from being swapped to disk (no mlock). Auto-lock: on screen off, after idle time, in the background |
| R9 | **The keyboard.** `IME_FLAG_NO_PERSONALIZED_LEARNING` is a request, not a ban; third-party keyboards may ignore it | A1 (cloud dictionary) | Planned: a warning on the unlock screen when the active keyboard is not on an allow list |
| R10 | **Flash memory.** Overwriting in place does not guarantee that the physical page is destroyed | out of scope | Said as it is; the protection is cryptographic (the key is out of reach without the password) |
| R11 | **A duress wipe as evidence.** An adversary who copied the file before the password was typed sees that data was destroyed. In some countries this is a crime (obstruction, destroying evidence) | A3 | Off by default; an explicit warning when it is set; it is no protection against a copy taken beforehand |
| R11a | **The "destroy on opening" rule and an early copy.** An adversary who copied the file before the decoy was opened, and learned its password, can read the rule: they see which blocks belonged to the destroyed vault (so they learn it existed), but not their content | A3 | The rule holds only locators, never an encryption key (spec §10.1) |
| R11b | **Attachments.** A photo from the gallery stays in the gallery. Large attachments make the file grow fast: a strong signal for an adversary with two snapshots (T5) | A1, A3×2 | The in-app camera (Camera2) and voice recording (MediaRecorder into a pipe) work only in memory; photos are re-encoded, EXIF with the location is dropped; a warning in the Attach menu |
| R11c | **Copies and exports leave the app's control.** A backup of everything lives its own life: a vault destroyed later on the phone (by hand or by the rule) stays in it, and old passwords open it. An exported vault is protected only by its own password, and its size shows how much data that vault holds: there are no neighbours inflating the file. The very presence of the file in Downloads or in the cloud shows that the app is used | A1, A3, A4 | The copy is an ordinary container without a signature, with a neutral file name. An export uses a fresh vault key: its password does not open the data on the phone. A strength meter for the export password. Warnings in the description of "Back up everything" and on restore |
| R12 | **Metadata.** The file size, its modification time, when the background job runs, the fact of installation, purchases in a store | all | Background chaff blurs the times of activity. Hidden vaults will never be the only paid feature: a purchase history must not point at them |
| R13 | **Sync (planned).** The provider sees the time and size of every upload; a duress wipe does not touch the remote copy, and the next sync brings the wiped data back | A4 | Sync is set up inside a vault and runs only while it is open; a warning for the combination of `sync` and `duress_wipe` |

## 6. Android hygiene

| Measure | Against |
|---|---|
| `FLAG_SECURE` on every window, unless the user allows screenshots; the recents preview is always hidden | screenshots, the recents preview, screen recording |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` and no autofill in every field | the keyboard's dictionary, suggestions (see R9) |
| `android:allowBackup="false"`, `dataExtractionRules` exclude everything | cloud and adb backup, transfer to a new device |
| No notifications at all | the lock screen, watches, notification sync |
| Clipboard: `ClipDescription.EXTRA_IS_SENSITIVE` (Android 13+), cleared after 60 s, or copying turned off | clipboard history, previews |
| Auto-lock in the background and by timer | a phone left unlocked |
| Drafts only in memory and in the container, no temporary files | plaintext left on the disk |
| No WebView; Markdown is rendered natively | the WebView cache, JS |
| Planned: biometrics for at most one vault, a decoy recommended | a finger pressed on the sensor by force |
| The same "Wrong password" message for any password that is not accepted, after the same pause | guessing by the interface's reaction |

## 7. What the README says

> Palimpsest is an experimental diary with deniable encryption. Not audited.
> It protects against a curious partner, a thief and a quick border check. It does not
> protect against a forensic lab, spyware, or someone who will keep demanding passwords
> until they believe you. If an adversary keeps copies of the file from different moments
> and knows the decoy password, a hidden vault can give itself away; details are in the
> [threat model](threat-model.md).
