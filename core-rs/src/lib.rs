//! Palimpsest container format v1 (docs/format-spec.md): one password field, several
//! vaults, none of which can be told apart from random chaff without its password.

uniffi::setup_scaffolding!();

pub mod attachment;
pub mod chaff;
mod container;
pub mod crypto;
pub mod format;
pub mod frame;
pub mod heap;
pub mod keys;
pub mod op;
mod session;
pub mod state;
pub mod tlv;

pub use container::{ArmWipeResult, ChangePasswordResult, Container, CoreError};
pub use session::{AttachmentInfo, EntryDetail, EntryKind, EntrySummary, Folder, PurgeMode, Session, TagCount};

#[cfg(test)]
mod tests;
