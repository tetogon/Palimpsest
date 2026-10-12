//! How many chaff blocks to add (spec §9). examples/t5.rs measures how well a policy hides
//! a second vault from a two-snapshot adversary and what it costs in storage.

use crate::crypto::random_below;

/// Uniform integers in [0, bound). The OS generator in production, a seeded one in simulations.
pub trait Rng {
    fn below(&mut self, bound: u32) -> u32;
}

pub struct OsRng;

impl Rng for OsRng {
    fn below(&mut self, bound: u32) -> u32 {
        random_below(bound)
    }
}

/// Rare large bursts give the unattributed growth a heavy tail; that, not the mean, is what
/// hides a hidden vault's writes (see docs/t5-simulation.md).
#[derive(Clone, Copy, Debug)]
pub struct Policy {
    /// Chance of a burst on every chaff event, per 10 000.
    pub burst_per_10k: u32,
    pub burst_min: u32,
    pub burst_max: u32,
}

/// Production policy.
pub const POLICY: Policy = Policy { burst_per_10k: 100, burst_min: 16, burst_max: 128 };

pub fn on_create() -> usize {
    16 + random_below(49) as usize
}

pub fn on_commit(written: usize) -> usize {
    on_commit_with(&mut OsRng, &POLICY, written)
}

pub fn on_session_end() -> usize {
    on_session_end_with(&mut OsRng, &POLICY)
}

pub fn background() -> usize {
    background_with(&mut OsRng, &POLICY)
}

/// `written` is the number of non-chaff blocks in the batch.
pub fn on_commit_with(r: &mut impl Rng, p: &Policy, written: usize) -> usize {
    let u = r.below(1_000_000) as f64 / 1_000_000.0;
    geometric(r) + (u * written as f64).ceil() as usize + burst(r, p)
}

pub fn on_session_end_with(r: &mut impl Rng, p: &Policy) -> usize {
    geometric(r) + burst(r, p)
}

/// Daily background job: half of the days add nothing at all.
pub fn background_with(r: &mut impl Rng, p: &Policy) -> usize {
    if r.below(2) == 0 {
        0
    } else {
        geometric(r) + burst(r, p)
    }
}

/// Mean blocks one burst draw adds, for expected-growth estimates.
pub fn burst_mean(p: &Policy) -> f64 {
    p.burst_per_10k as f64 / 10_000.0 * (p.burst_min + p.burst_max) as f64 / 2.0
}

/// Geometric(½) on {0, 1, 2, …}, mean 1.
fn geometric(r: &mut impl Rng) -> usize {
    let mut n = 0;
    while n < 64 && r.below(2) == 0 {
        n += 1;
    }
    n
}

fn burst(r: &mut impl Rng, p: &Policy) -> usize {
    if r.below(10_000) < p.burst_per_10k {
        (p.burst_min + r.below(p.burst_max - p.burst_min + 1)) as usize
    } else {
        0
    }
}
