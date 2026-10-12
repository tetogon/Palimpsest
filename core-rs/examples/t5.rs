//! Spec §12 T5: can a two-snapshot adversary who knows the decoy password tell whether a
//! second vault exists?
//!
//!     cargo run --release --example t5
//!
//! Model. Users A (decoy only) and B (decoy + hidden vault) behave the same in the decoy.
//! Between two copies of the file, D days apart, the adversary sees:
//!   * the decoy's new operations: how many blocks, how many writing sessions (session ids);
//!   * `new_unknown`: new blocks it cannot attribute (chaff, plus the hidden vault for B);
//!   * `changed_unknown`: old unattributed blocks whose bytes changed (purges in the hidden
//!     vault in immediate mode; chaff never changes).
//! Chaff counts come from the production policy (`palimpsest_core::chaff`) with a seeded RNG.
//! Detectors:
//!   1. changed_unknown > 0  →  "hidden vault". Never fires for A.
//!   2. growth: new_unknown minus the chaff the visible decoy activity explains; ROC AUC
//!      between A and B and the detection rate at 5 % false alarms.
//! This is a model of block counts, not of real files; it sets the policy's limits.

use palimpsest_core::chaff::{self, Policy, Rng};
use palimpsest_core::format::{BLOCK_SIZE, FRAME_CAP};

struct SplitMix(u64);

impl SplitMix {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    fn unit(&mut self) -> f64 {
        (self.next() >> 11) as f64 / (1u64 << 53) as f64
    }

    fn poisson(&mut self, lambda: f64) -> u32 {
        let l = (-lambda).exp();
        let (mut k, mut p) = (0, 1.0);
        loop {
            p *= self.unit();
            if p <= l {
                return k;
            }
            k += 1;
        }
    }

    /// Blocks of one entry revision: body ~ lognormal, median 700 bytes, plus ~100 bytes of op header.
    fn entry_blocks(&mut self) -> usize {
        let (u1, u2) = (self.unit().max(1e-12), self.unit());
        let normal = (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos();
        let bytes = 700.0 * normal.exp() + 100.0;
        (bytes / FRAME_CAP as f64).ceil().max(1.0) as usize
    }
}

impl Rng for SplitMix {
    fn below(&mut self, bound: u32) -> u32 {
        (self.next() % bound as u64) as u32
    }
}

#[derive(Clone, Copy)]
struct Scenario {
    days: u32,
    decoy_sessions_per_day: f64,
    hidden_sessions_per_day: f64,
    manual_purge: bool,
    policy: Policy,
}

/// Entries written per session ~ Poisson(0.8): about half of the sessions only read.
const ENTRIES_PER_SESSION: f64 = 0.8;
/// Chance that a write edits an entry from an earlier session (instead of a new entry).
const EDIT_OLD: f64 = 0.15;

struct Observation {
    new_unknown: f64,
    changed_unknown: usize,
    /// Chaff explained by what the adversary sees of the decoy.
    expected_from_decoy: f64,
}

/// One hidden-vault session: its data blocks are unattributable to the decoy holder.
fn hidden_session(r: &mut SplitMix, s: &Scenario, new_unknown: &mut usize, changed: &mut usize) {
    for _ in 0..r.poisson(ENTRIES_PER_SESSION) {
        let b = r.entry_blocks();
        *new_unknown += b + chaff::on_commit_with(r, &s.policy, b);
        if !s.manual_purge && r.unit() < EDIT_OLD {
            *changed += r.entry_blocks(); // the replaced revision is overwritten in place
        }
    }
    *new_unknown += chaff::on_session_end_with(r, &s.policy);
}

/// What the adversary expects from the visible decoy activity and the public policy:
/// commit ≈ 1 + (b/2 + ½) + burst, session end ≈ 1 + burst, background ≈ ½(1 + burst) a day.
fn expected_chaff(p: &Policy, decoy_commits: &[usize], writing_sessions: usize, days: u32) -> f64 {
    let bm = chaff::burst_mean(p);
    let commits: f64 = decoy_commits.iter().map(|&b| 1.0 + b as f64 / 2.0 + 0.5 + bm).sum();
    commits + (1.0 + bm) * writing_sessions as f64 + 0.5 * (1.0 + bm) * days as f64
}

fn observe(r: &mut SplitMix, s: &Scenario, hidden: bool) -> Observation {
    let (mut new_unknown, mut changed) = (0usize, 0usize);
    let mut decoy_commits = Vec::new();
    let mut writing_sessions = 0;
    for _ in 0..s.days {
        new_unknown += chaff::background_with(r, &s.policy);
        for _ in 0..r.poisson(s.decoy_sessions_per_day) {
            let writes = r.poisson(ENTRIES_PER_SESSION);
            for _ in 0..writes {
                let b = r.entry_blocks();
                decoy_commits.push(b);
                new_unknown += chaff::on_commit_with(r, &s.policy, b);
            }
            new_unknown += chaff::on_session_end_with(r, &s.policy);
            if writes > 0 {
                writing_sessions += 1;
            }
        }
        if hidden {
            for _ in 0..r.poisson(s.hidden_sessions_per_day) {
                hidden_session(r, s, &mut new_unknown, &mut changed);
            }
        }
    }
    Observation {
        new_unknown: new_unknown as f64,
        changed_unknown: changed,
        expected_from_decoy: expected_chaff(&s.policy, &decoy_commits, writing_sessions, s.days),
    }
}

/// Mann–Whitney AUC: P(score_B > score_A), ties counted half.
fn auc(a: &[f64], b: &[f64]) -> f64 {
    let mut all: Vec<(f64, bool)> = a.iter().map(|&x| (x, false)).chain(b.iter().map(|&x| (x, true))).collect();
    all.sort_by(|x, y| x.0.partial_cmp(&y.0).unwrap());
    let (mut rank_sum_b, mut i) = (0.0, 0);
    while i < all.len() {
        let mut j = i;
        while j < all.len() && all[j].0 == all[i].0 {
            j += 1;
        }
        let avg = (i + j + 1) as f64 / 2.0; // ranks are 1-based
        rank_sum_b += avg * all[i..j].iter().filter(|e| e.1).count() as f64;
        i = j;
    }
    let (na, nb) = (a.len() as f64, b.len() as f64);
    (rank_sum_b - nb * (nb + 1.0) / 2.0) / (na * nb)
}

fn tpr_at_fpr(a: &[f64], b: &[f64], fpr: f64) -> f64 {
    let mut sorted = a.to_vec();
    sorted.sort_by(|x, y| x.partial_cmp(y).unwrap());
    let threshold = sorted[((1.0 - fpr) * (sorted.len() - 1) as f64) as usize];
    b.iter().filter(|&&x| x > threshold).count() as f64 / b.len() as f64
}

fn run(r: &mut SplitMix, s: Scenario) -> (f64, f64, f64, f64) {
    const USERS: usize = 20_000;
    let a: Vec<Observation> = (0..USERS).map(|_| observe(r, &s, false)).collect();
    let b: Vec<Observation> = (0..USERS).map(|_| observe(r, &s, true)).collect();
    assert!(a.iter().all(|o| o.changed_unknown == 0), "chaff never changes");
    let caught = b.iter().filter(|o| o.changed_unknown > 0).count() as f64 / USERS as f64;
    let ra: Vec<f64> = a.iter().map(|o| o.new_unknown - o.expected_from_decoy).collect();
    let rb: Vec<f64> = b.iter().map(|o| o.new_unknown - o.expected_from_decoy).collect();
    let mb_per_year = a.iter().map(|o| o.new_unknown).sum::<f64>() / USERS as f64 / s.days as f64 * 365.0
        * BLOCK_SIZE as f64 / 1e6;
    (caught, auc(&ra, &rb), tpr_at_fpr(&ra, &rb, 0.05), mb_per_year)
}

fn main() {
    let mut r = SplitMix(0x5EED_1234);
    let base = Scenario {
        days: 30,
        decoy_sessions_per_day: 1.0,
        hidden_sessions_per_day: 1.0,
        manual_purge: false,
        policy: chaff::POLICY,
    };

    println!("## Current policy {:?}\n", chaff::POLICY);
    println!("| days | hidden sessions/day | purge | changed-block detector | growth AUC | growth detection at 5% false alarms |");
    println!("|---:|---:|---|---:|---:|---:|");
    for days in [7, 30, 90] {
        for hidden in [0.2, 1.0, 3.0] {
            for manual in [false, true] {
                let s = Scenario { days, hidden_sessions_per_day: hidden, manual_purge: manual, ..base };
                let (caught, auc, tpr, _) = run(&mut r, s);
                println!(
                    "| {days} | {hidden} | {} | {:.1}% | {:.3} | {:.1}% |",
                    if manual { "manual" } else { "immediate" },
                    caught * 100.0,
                    auc,
                    tpr * 100.0
                );
            }
        }
    }

    let candidates = [
        chaff::POLICY,
        Policy { burst_per_10k: 100, burst_min: 16, burst_max: 512 },
        Policy { burst_per_10k: 100, burst_min: 64, burst_max: 1024 },
        Policy { burst_per_10k: 50, burst_min: 64, burst_max: 2048 },
        Policy { burst_per_10k: 20, burst_min: 256, burst_max: 4096 },
    ];
    println!("\n## Burst policies, manual purge, decoy 1 session/day\n");
    println!("| bursts | chaff MB/year | AUC 30 d, hidden 0.2/day | AUC 30 d, 1/day | AUC 30 d, 3/day | AUC 90 d, 1/day | detection at 5% FA, 30 d, 1/day |");
    println!("|---|---:|---:|---:|---:|---:|---:|");
    for p in candidates {
        let at = |r: &mut SplitMix, days, hidden| run(r, Scenario { days, hidden_sessions_per_day: hidden, manual_purge: true, policy: p, ..base });
        let (_, a02, _, mb) = at(&mut r, 30, 0.2);
        let (_, a1, t1, _) = at(&mut r, 30, 1.0);
        let (_, a3, _, _) = at(&mut r, 30, 3.0);
        let (_, a90, _, _) = at(&mut r, 90, 1.0);
        println!(
            "| {:.1}% × {}–{} | {mb:.0} | {a02:.3} | {a1:.3} | {a3:.3} | {a90:.3} | {:.1}% |",
            p.burst_per_10k as f64 / 100.0,
            p.burst_min,
            p.burst_max,
            t1 * 100.0
        );
    }
}
