# T5: an adversary with two snapshots of the file

A check from the [specification](format-spec.md) (§12, T5). The question: can someone with
**two copies of the file taken at different times and the decoy password** tell that the
container holds a second vault? This is adversary A3 on two trips, or A4 (a sync provider)
together with the decoy password; see the [threat model](threat-model.md), R3–R4.

Run: `cd core-rs && cargo run --release --example t5`
(the code is [core-rs/examples/t5.rs](../core-rs/examples/t5.rs)). 20,000 users per cell,
fixed seed.

## The model

* Users A (decoy only) and B (decoy + hidden vault) use the decoy the same way: on average
  1 session a day, 0.8 writes per session (about half the sessions only read).
* B's hidden vault: 0.2 / 1 / 3 sessions a day with the same writes. In 15% of the writes an
  entry from an earlier session is edited.
* Entry size: log-normal, median 700 bytes; almost always one block.
* Chaff comes from the **production policy** `palimpsest_core::chaff` with a deterministic
  generator.

The adversary sees:
* the decoy's new operations (how many blocks and how many writing sessions: operations carry
  a `session_id`);
* the number of new unknown blocks;
* the number of old unknown blocks whose bytes changed.

Detectors:
1. **Changed blocks.** Chaff never changes, so any old unknown block that changed means a
   hidden vault. No false positives.
2. **Growth.** New unknown blocks minus the chaff explained by the decoy's visible activity
   and the public policy. Given as ROC AUC (0.5 = guessing, 1.0 = perfect) and the share
   caught at 5% false alarms.

This is a model of block counts, not of real files. It gives an **upper bound** for this
adversary and a simple detector; a smart adversary with more snapshots is stronger.

## Result 1: the production policy (bursts of 0.5% × 64–2048 blocks, since 0.3.1)

| days between snapshots | hidden sessions a day | erasing | changed-block detector | growth AUC | caught by growth at 5% false |
|---:|---:|---|---:|---:|---:|
| 7 | 0.2 | immediate | 14.6% | 0.55 | 6% |
| 7 | 0.2 | manual | **0%** | 0.55 | 6% |
| 7 | 1 | immediate | 56% | 0.71 | 9% |
| 7 | 1 | manual | **0%** | 0.71 | 9% |
| 30 | 0.2 | immediate | 50% | 0.57 | 6% |
| 30 | 0.2 | manual | **0%** | 0.57 | 6% |
| 30 | 1 | immediate | 97% | 0.75 | 14% |
| 30 | 1 | manual | **0%** | 0.75 | 13% |
| 30 | 3 | immediate | 100% | 0.86 | 34% |
| 30 | 3 | manual | **0%** | 0.86 | 34% |
| 90 | 0.2 | manual | **0%** | 0.56 | 7% |
| 90 | 1 | immediate | 100% | 0.74 | 21% |
| 90 | 1 | manual | **0%** | 0.74 | 22% |
| 90 | 3 | manual | **0%** | 0.93 | 65% |

Conclusions:
* **Immediate erasing gives a hidden vault away** if old entries in it are edited: within a
  month almost certainly. Manual mode removes this channel entirely.
* **Growth remains.** While the hidden vault writes much less than the decoy (0.2 sessions a
  day against 1), it is close to guessing (AUC 0.56–0.57). With the same activity as the decoy
  the adversary catches about one in eight such users within a month and one in five within
  three; with three times the decoy's activity, a third and two thirds.

## Result 2: larger and rarer bursts (manual erasing)

How the production policy was chosen. The policy before 0.3.1 is the first row.

| bursts | chaff, MB/year | AUC 30 d, 0.2/day | AUC 30 d, 1/day | AUC 30 d, 3/day | AUC 90 d, 1/day | caught at 5%, 30 d, 1/day |
|---|---:|---:|---:|---:|---:|---:|
| 1% × 16–128 (before 0.3.1) | 7 | 0.65 | 0.91 | 1.00 | 0.99 | 48% |
| 1% × 16–512 | 14 | 0.59 | 0.77 | 0.93 | 0.86 | 20% |
| 1% × 64–1024 | 23 | 0.56 | 0.72 | 0.89 | 0.80 | 16% |
| **0.5% × 64–2048 (production)** | 23 | 0.57 | 0.75 | 0.86 | 0.74 | 14% |
| 0.2% × 256–4096 | 19 | 0.60 | 0.84 | 0.90 | 0.77 | 10% |

What hides a vault is not the amount of chaff but its **spread**: rare large bursts make the
file's growth noisy, and the hidden vault's share of it gets lost. Three times the space
(7 → 23 MB a year) brings the share caught within a month down from 48% to 14–16%.
0.5% × 64–2048 was chosen as the most even: it is not the best in any single column, but it
holds up best as the snapshots get further apart and the hidden vault gets busier. No policy
reaches indistinguishability (AUC 0.5) for an active hidden vault: any chaff without a
secret, which cannot be kept outside the vaults, averages out over time.

## Result 3: one copy and the decoy password are two snapshots too

One copy is enough for the growth detector once the decoy password is known. The first
"snapshot" is the new container, whose content is known (16–64 blocks of chaff); the interval
is the file's age; the decoy's operations carry their times and session ids, so the adversary
knows its activity over the whole life of the file. Same model, manual erasing:

| file age, days | hidden sessions a day | growth AUC | caught at 5% false |
|---:|---:|---:|---:|
| 365 | 0.2 | 0.60 | 10% |
| 365 | 1 | 0.88 | 49% |
| 365 | 3 | 1.00 | 99% |
| 730 | 1 | 0.95 | 75% |

This model is optimistic for the user: it only counts blocks. Blocks are appended at the end
and shuffled only within one batch, so a block's position in the file follows time. The decoy's
dated blocks mark the file out into periods, and the growth of unknown blocks can be read
period by period; a photo in a hidden vault shows as a dated jump. An earlier version of this
document and of the threat model promised deniability from one snapshot with any set of
passwords. That holds only for a copy **without** a password (T4); this result was pointed out
in an outside review.

## What this means for the user

* Against **one** copy **without** a password (theft, a copy taken before you typed
  anything) deniability holds: see the indistinguishability test T4.
* Against one copy **with** the decoy password, and against two copies:
  * manual erasing is a must for a hidden vault;
  * the decoy should be used more actively than the hidden vault, over the file's whole life;
  * the older the file (or the further apart the copies), the worse;
  * large attachments in a hidden vault are the strongest signal.
* This is a limit of every hidden-volume scheme with a growing file (in VeraCrypt the same
  adversary compares sectors instead). The README and the threat model say so plainly.
