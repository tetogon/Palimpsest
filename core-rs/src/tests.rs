//! Container-level behaviour and the single-snapshot indistinguishability check (spec §12 T4).

use crate::container::{restore_container, ArmWipeResult, ChangePasswordResult, Container, ImportResult};
use crate::crypto::Key;
use crate::format::*;
use crate::heap::Heap;
use crate::keys::{locator, PasswordKeys};
use crate::session::{EntryKind, NoteLink, Session};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;

struct Fixture {
    _dir: tempfile::TempDir,
    path: PathBuf,
}

impl Fixture {
    fn new() -> Fixture {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("data.bin");
        Fixture { _dir: dir, path }
    }

    /// A fresh Container object over the same file, as after an app restart.
    fn open(&self) -> Arc<Container> {
        Container::open_or_create(self.path.to_string_lossy().into()).unwrap()
    }

    fn len(&self) -> u64 {
        fs::metadata(&self.path).unwrap().len()
    }
}

fn create(c: &Container, pw: &str) -> Arc<Session> {
    c.create_vault(pw.into()).unwrap().expect("password free")
}

fn unlock(c: &Container, pw: &str) -> Option<Arc<Session>> {
    c.unlock(pw.into()).unwrap()
}

fn titles(s: &Session) -> Vec<String> {
    s.entries().unwrap().into_iter().map(|e| e.title).collect()
}

/// D = decoy, R = real; R has edited entries, so it also owns PAD blocks.
fn two_vaults(c: &Container) {
    let d = create(c, "D");
    d.save(None, EntryKind::Note, None, "groceries".into(), "".into()).unwrap();
    d.lock().unwrap();
    let r = create(c, "R");
    let id = r.save(None, EntryKind::Note, None, "secret".into(), "x".repeat(10_000)).unwrap();
    r.save(Some(id), EntryKind::Note, None, "secret".into(), "edited".into()).unwrap();
    r.lock().unwrap();
}

#[test]
fn new_container_already_has_chaff() {
    let f = Fixture::new();
    assert!(f.open().block_count().unwrap() >= 16);
    assert_eq!(f.len() % BLOCK_SIZE as u64, 0);
}

#[test]
fn wrong_password_opens_nothing_and_password_cannot_be_reused() {
    let f = Fixture::new();
    let c = f.open();
    create(&c, "right").lock().unwrap();
    assert!(unlock(&c, "wrong").is_none());
    assert!(c.create_vault("right".into()).unwrap().is_none());
}

#[test]
fn entries_survive_reopen_and_edits_replace() {
    let f = Fixture::new();
    let s = create(&f.open(), "pw");
    let id = s.save(None, EntryKind::Note, None, "Day 1".into(), "hello".into()).unwrap();
    s.save(Some(id.clone()), EntryKind::Note, None, "Day 1".into(), "hello, edited\nsecond line".into()).unwrap();
    s.lock().unwrap();
    assert!(s.is_locked());

    let again = unlock(&f.open(), "pw").unwrap();
    let list = again.entries().unwrap();
    assert_eq!(list.len(), 1);
    assert_eq!(list[0].preview, "hello, edited\nsecond line");
    assert_eq!(again.entry(id).unwrap().unwrap().body, "hello, edited\nsecond line");
}

#[test]
fn vaults_do_not_see_each_other() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    assert_eq!(titles(&unlock(&c, "D").unwrap()), ["groceries"]);
    assert_eq!(titles(&unlock(&c, "R").unwrap()), ["secret"]);
}

#[test]
fn delete_entry_and_locked_session_refuses_work() {
    let f = Fixture::new();
    let s = create(&f.open(), "pw");
    let id = s.save(None, EntryKind::Note, None, "t".into(), "b".into()).unwrap();
    s.delete(id.clone()).unwrap();
    assert!(s.entry(id).unwrap().is_none());
    s.lock().unwrap();
    assert!(s.save(None, EntryKind::Note, None, "x".into(), "y".into()).is_err());
}

#[test]
fn armed_wipe_fires_once_and_decoy_keeps_working() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    let d = unlock(&c, "D").unwrap();
    assert_eq!(c.arm_wipe(d.clone(), "R".into()).unwrap(), ArmWipeResult::Armed);
    assert!(d.wipe_armed().unwrap());
    d.lock().unwrap();
    assert_eq!(titles(&unlock(&c, "R").unwrap()), ["secret"]); // opening R does not fire it

    let before = f.len();
    let shown = unlock(&c, "D").unwrap();
    assert!(f.len() >= before); // shredded in place, never truncated
    assert_eq!(titles(&shown), ["groceries"]);
    assert!(!shown.wipe_armed().unwrap());
    shown.lock().unwrap();

    assert!(unlock(&c, "R").is_none());
    assert_eq!(titles(&unlock(&c, "D").unwrap()), ["groceries"]);
}

#[test]
fn disarm_and_validation() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    let d = unlock(&c, "D").unwrap();
    assert_eq!(c.arm_wipe(d.clone(), "nope".into()).unwrap(), ArmWipeResult::NotFound);
    assert_eq!(c.arm_wipe(d.clone(), "D".into()).unwrap(), ArmWipeResult::SameVault);
    c.arm_wipe(d.clone(), "R".into()).unwrap();
    c.disarm_wipe(d.clone()).unwrap();
    d.lock().unwrap();
    unlock(&c, "D").unwrap().lock().unwrap();
    assert_eq!(titles(&unlock(&c, "R").unwrap()), ["secret"]);
}

#[test]
fn delete_vault_shreds_only_itself() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    c.delete_vault(unlock(&c, "R").unwrap()).unwrap();
    assert!(unlock(&c, "R").is_none());
    assert_eq!(titles(&unlock(&c, "D").unwrap()), ["groceries"]);
}

#[test]
fn change_password_retires_the_old_one() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    let s = unlock(&c, "R").unwrap();
    assert_eq!(c.change_password(s.clone(), "D".into(), "R2".into()).unwrap(), ChangePasswordResult::WrongPassword);
    assert_eq!(c.change_password(s.clone(), "R".into(), "D".into()).unwrap(), ChangePasswordResult::PasswordTaken);
    assert_eq!(c.change_password(s.clone(), "R".into(), "R2".into()).unwrap(), ChangePasswordResult::Changed);
    s.save(None, EntryKind::Note, None, "after change".into(), "".into()).unwrap();
    s.lock().unwrap();
    assert!(unlock(&c, "R").is_none());
    let mut t = titles(&unlock(&c, "R2").unwrap());
    t.sort();
    assert_eq!(t, ["after change", "secret"]);
    c.delete_vault(unlock(&c, "R2").unwrap()).unwrap();
    assert!(unlock(&c, "R2").is_none());
}

#[test]
fn reclaim_space_shrinks_file_and_keeps_everything_working() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    let r = unlock(&c, "R").unwrap();
    let pads = r.reclaimable_blocks().unwrap();
    assert!(pads >= 3, "the 10 000-byte revision left {pads} PADs");
    let before = f.len();
    assert_eq!(c.reclaim_space(r.clone()).unwrap(), pads);
    assert_eq!(f.len(), before - pads as u64 * BLOCK_SIZE as u64);
    assert_eq!(r.reclaimable_blocks().unwrap(), 0);

    // Indices were remapped: further edits, password change and delete still hit the right blocks.
    let id = r.entries().unwrap()[0].entry_id.clone();
    r.save(Some(id), EntryKind::Note, None, "secret".into(), "after reclaim".into()).unwrap();
    assert_eq!(c.change_password(r.clone(), "R".into(), "R3".into()).unwrap(), ChangePasswordResult::Changed);
    r.lock().unwrap();
    assert_eq!(unlock(&c, "R3").unwrap().entries().unwrap()[0].preview, "after reclaim");
    assert_eq!(titles(&unlock(&c, "D").unwrap()), ["groceries"]);
}

#[test]
fn manual_purge_never_rewrites_old_blocks_until_asked() {
    use crate::session::PurgeMode;
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let id = s.save(None, EntryKind::Note, None, "t".into(), "v1".into()).unwrap();
    s.set_purge_mode(PurgeMode::Manual).unwrap();
    s.lock().unwrap();

    let s = unlock(&c, "pw").unwrap();
    assert_eq!(s.purge_mode().unwrap(), PurgeMode::Manual);
    let before = fs::read(&f.path).unwrap();
    s.save(Some(id.clone()), EntryKind::Note, None, "t".into(), "v2".into()).unwrap(); // replaces v1 from an earlier session
    s.save(Some(id.clone()), EntryKind::Note, None, "t".into(), "v3".into()).unwrap(); // replaces v2, born now: erased at once
    let after = fs::read(&f.path).unwrap();
    assert_eq!(after[..before.len()], before[..], "an old block changed in manual mode");
    assert_eq!(s.pending_purge_blocks().unwrap(), 1, "only v1 should wait");
    s.lock().unwrap();

    let s = unlock(&c, "pw").unwrap();
    assert_eq!(s.pending_purge_blocks().unwrap(), 1, "v1 still waits after reopening");
    assert_eq!(s.entry(id).unwrap().unwrap().body, "v3");
    assert_eq!(s.purge_now().unwrap(), 1);
    assert_eq!(s.pending_purge_blocks().unwrap(), 0);
    assert_ne!(fs::read(&f.path).unwrap()[..before.len()], before[..]);
}

#[test]
fn settings_revisions_are_erased_even_in_manual_mode() {
    use crate::session::PurgeMode;
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    let d = unlock(&c, "D").unwrap();
    d.set_purge_mode(PurgeMode::Manual).unwrap();
    d.lock().unwrap();
    let d = unlock(&c, "D").unwrap();
    c.arm_wipe(d.clone(), "R".into()).unwrap();
    d.lock().unwrap();
    // The rule fires; the revision holding R's locators must not linger as a pending block.
    let d = unlock(&c, "D").unwrap();
    assert!(!d.wipe_armed().unwrap());
    assert_eq!(d.pending_purge_blocks().unwrap(), 0);
}

#[test]
fn kinds_dates_and_todo_done_survive_reopen() {
    let f = Fixture::new();
    let s = create(&f.open(), "pw");
    let note = s.save(None, EntryKind::Note, None, "note".into(), "a\n\nb\nc\nd\ne".into()).unwrap();
    let diary = s.save(None, EntryKind::Diary, None, "day".into(), "".into()).unwrap();
    let todo = s.save(None, EntryKind::Todo, None, "buy milk".into(), "".into()).unwrap();
    s.set_done(todo.clone(), true).unwrap();
    s.set_done(todo.clone(), true).unwrap(); // no-op
    // Editing keeps the kind and the done flag.
    s.save(Some(todo.clone()), EntryKind::Note, None, "buy oat milk".into(), "".into()).unwrap();
    s.lock().unwrap();

    let s = unlock(&f.open(), "pw").unwrap();
    let by_id = |id: &str| s.entries().unwrap().into_iter().find(|e| e.entry_id == id).unwrap();
    let (n, d, t) = (by_id(&note), by_id(&diary), by_id(&todo));
    assert_eq!((n.kind, d.kind, t.kind), (EntryKind::Note, EntryKind::Diary, EntryKind::Todo));
    assert_eq!(n.preview, "a\nb\nc\nd", "four non-blank lines");
    assert!(n.diary_date.is_none() && d.diary_date.is_some());
    assert!(t.done && t.title == "buy oat milk");
    assert!(t.updated_ms >= t.created_ms);
    s.set_done(todo.clone(), false).unwrap();
    assert!(!s.entry(todo).unwrap().unwrap().done);
}

#[test]
fn delete_many_in_one_commit() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let ids: Vec<String> = (0..5).map(|i| s.save(None, EntryKind::Note, None, format!("n{i}"), "x".into()).unwrap()).collect();
    let deleted = s
        .delete_many(vec![ids[0].clone(), ids[2].clone(), ids[2].clone(), "00".repeat(16), ids[4].clone()])
        .unwrap();
    assert_eq!(deleted, 3, "duplicates and unknown ids are skipped");
    s.lock().unwrap();
    let mut left = titles(&unlock(&c, "pw").unwrap());
    left.sort();
    assert_eq!(left, ["n1", "n3"]);
}

#[test]
fn folders_pins_colors_survive_reopen_and_folder_delete_frees_notes() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let work = s.create_folder("Работа".into()).unwrap();
    let home = s.create_folder("Дом".into()).unwrap();
    let a = s.save(None, EntryKind::Note, Some(work.clone()), "a".into(), "".into()).unwrap();
    let b = s.save(None, EntryKind::Note, None, "b".into(), "".into()).unwrap();
    assert_eq!(s.move_to_folder(vec![b.clone()], Some(home.clone())).unwrap(), 1);
    assert_eq!(s.set_pinned(vec![a.clone(), b.clone()], true).unwrap(), 2);
    assert_eq!(s.set_pinned(vec![a.clone()], true).unwrap(), 0, "unchanged entries are skipped");
    assert_eq!(s.set_color(vec![a.clone()], 3).unwrap(), 1);
    assert!(s.set_color(vec![a.clone()], 9).is_err());
    s.rename_folder(home.clone(), "Дача".into()).unwrap();
    s.lock().unwrap();

    let s = unlock(&c, "pw").unwrap();
    let folders = s.folders().unwrap();
    assert_eq!(folders.iter().map(|f| (f.name.as_str(), f.note_count)).collect::<Vec<_>>(), [("Дача", 1), ("Работа", 1)]);
    assert!(s.entries().unwrap().iter().all(|e| e.title != "Работа"), "folders are not entries");
    let get = |id: &str| s.entries().unwrap().into_iter().find(|e| e.entry_id == id).unwrap();
    assert_eq!((get(&a).folder_id, get(&a).color, get(&a).pinned), (Some(work.clone()), 3, true));

    s.delete_folder(work.clone()).unwrap();
    assert_eq!(s.folders().unwrap().len(), 1);
    assert_eq!(get(&a).folder_id, None, "notes of a deleted folder stay, without a folder");
}

#[test]
fn trash_restore_empty_and_expiry() {
    use crate::format::{T_TRASHED, TRASH_RETENTION_MS};
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let ids: Vec<String> = (0..4).map(|i| s.save(None, EntryKind::Note, None, format!("n{i}"), "".into()).unwrap()).collect();
    assert_eq!(s.trash(ids[..3].to_vec()).unwrap(), 3);
    assert_eq!(s.restore(vec![ids[0].clone()]).unwrap(), 1);
    let trashed = |s: &Session| s.entries().unwrap().into_iter().filter(|e| e.trashed_ms.is_some()).count();
    assert_eq!(trashed(&s), 2);
    // Backdate one trashed entry past the retention period.
    {
        let heap = s.shared.heap.lock().unwrap();
        let old = (1_000u64).to_le_bytes().to_vec();
        let id = crate::format::parse_id(&ids[1]).unwrap();
        s.inner.lock().unwrap().set_attr_many(&heap, &s.shared.cipher, &[id], T_TRASHED, Some(old)).unwrap();
    }
    assert!(TRASH_RETENTION_MS > 0);
    s.lock().unwrap();

    let s = unlock(&c, "pw").unwrap();
    assert_eq!(s.entries().unwrap().len(), 3, "the expired one is gone after unlock");
    assert_eq!(trashed(&s), 1);
    assert_eq!(s.empty_trash().unwrap(), 1);
    let mut left = titles(&s);
    left.sort();
    assert_eq!(left, ["n0", "n3"]);
}

#[test]
fn attachments_round_trip_and_survive_reopen() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let id = s.save(None, EntryKind::Note, None, "photo".into(), "".into()).unwrap();
    let data: Vec<u8> = (0..10_000u32).map(|i| (i * 7 % 251) as u8).collect(); // 3 parts
    let a = s.add_attachment(id.clone(), data.clone(), "image/jpeg".into(), Some(vec![1, 2, 3]), 0, 640, 480).unwrap();
    let voice = s.add_attachment(id.clone(), vec![9; 100], "audio/aac".into(), None, 4_200, 0, 0).unwrap();
    assert!(s.add_attachment(id.clone(), vec![], "x".into(), None, 0, 0, 0).is_err());
    s.lock().unwrap();

    let s = unlock(&c, "pw").unwrap();
    let e = s.entry(id.clone()).unwrap().unwrap();
    assert_eq!(e.attachments, vec![a.clone(), voice.clone()]);
    assert_eq!(e.title, "photo", "adding attachments keeps the text");
    assert_eq!(s.attachment_data(a.attachment_id.clone()).unwrap(), data);
    assert_eq!(s.thumbnail_data(a.attachment_id.clone()).unwrap(), Some(vec![1, 2, 3]));
    assert_eq!(s.thumbnail_data(voice.attachment_id.clone()).unwrap(), None);
    assert_eq!(s.entries().unwrap()[0].attachments.len(), 2);
}

#[test]
fn attachments_reorder() {
    let f = Fixture::new();
    let s = create(&f.open(), "pw");
    let id = s.save(None, EntryKind::Note, None, "n".into(), "".into()).unwrap();
    let ids: Vec<String> = (0..3)
        .map(|i| s.add_attachment(id.clone(), vec![i; 10], "audio/aac".into(), None, 1, 0, 0).unwrap().attachment_id)
        .collect();
    s.reorder_attachments(id.clone(), vec![ids[2].clone(), ids[0].clone()]).unwrap();
    let order: Vec<String> = s.entry(id).unwrap().unwrap().attachments.into_iter().map(|a| a.attachment_id).collect();
    assert_eq!(order, [ids[2].clone(), ids[0].clone(), ids[1].clone()]);
}

#[test]
fn removed_or_deleted_attachments_are_erased() {
    use crate::session::PurgeMode;
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let id = s.save(None, EntryKind::Note, None, "n".into(), "".into()).unwrap();
    let a = s.add_attachment(id.clone(), vec![5; 9_000], "image/jpeg".into(), Some(vec![6; 50]), 0, 1, 1).unwrap();
    s.lock().unwrap();

    // Manual mode: removing an attachment from an earlier session waits for "erase now".
    let s = unlock(&c, "pw").unwrap();
    s.set_purge_mode(PurgeMode::Manual).unwrap();
    s.remove_attachment(id.clone(), a.attachment_id.clone()).unwrap();
    assert!(s.entry(id.clone()).unwrap().unwrap().attachments.is_empty());
    assert_eq!(s.pending_purge_blocks().unwrap(), 1 + 3 + 1, "old revision + 3 file parts + thumbnail");
    s.purge_now().unwrap();
    assert_eq!(s.pending_purge_blocks().unwrap(), 0);
    assert!(s.attachment_data(a.attachment_id.clone()).is_err());

    // Immediate mode: deleting the entry erases its attachment at once.
    s.set_purge_mode(PurgeMode::Immediate).unwrap();
    let b = s.add_attachment(id.clone(), vec![7; 100], "audio/aac".into(), None, 1_000, 0, 0).unwrap();
    s.delete_many(vec![id.clone()]).unwrap();
    assert_eq!(s.pending_purge_blocks().unwrap(), 0);
    s.lock().unwrap();
    let s = unlock(&c, "pw").unwrap();
    assert!(s.attachment_data(b.attachment_id).is_err());
    assert_eq!(s.reclaimable_blocks().unwrap() > 0, true);
}

#[test]
fn tags_normalize_count_and_search() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let a = s.save(None, EntryKind::Note, None, "a".into(), "milk".into()).unwrap();
    let b = s.save(None, EntryKind::Diary, None, "b".into(), "milk".into()).unwrap();
    s.set_tags(a.clone(), vec!["#Работа".into(), " работа ".into(), "big idea".into(), "".into()]).unwrap();
    assert_eq!(s.entry(a.clone()).unwrap().unwrap().tags, ["Работа", "big-idea"]);
    assert_eq!(s.add_tag(vec![a.clone(), b.clone()], "работа".into()).unwrap(), 1, "a already has it");
    s.lock().unwrap();

    let s = unlock(&c, "pw").unwrap();
    let tags: Vec<(String, u32)> = s.tags().unwrap().into_iter().map(|t| (t.name, t.count)).collect();
    assert_eq!(tags, [("Работа".to_string(), 2), ("big-idea".to_string(), 1)]);
    let found = |q: &str| s.search(q.into()).unwrap().len();
    assert_eq!(found("#работа"), 2);
    assert_eq!(found("#big-idea milk"), 1);
    assert_eq!(found("#nope"), 0);
    assert_eq!(found("#"), 0, "a bare # is an ordinary word");
}

#[test]
fn search_folds_case_accents_and_yo() {
    let f = Fixture::new();
    let s = create(&f.open(), "pw");
    s.save(None, EntryKind::Note, None, "Ёлка".into(), "Café в Москве".into()).unwrap();
    s.save(None, EntryKind::Note, None, "Мой день".into(), "ничего".into()).unwrap();
    let titles = |q: &str| {
        let mut t: Vec<String> = s.search(q.into()).unwrap().into_iter().map(|e| e.title).collect();
        t.sort();
        t
    };
    assert_eq!(titles("елка cafe"), ["Ёлка"]);
    assert_eq!(titles("МОСКВЕ"), ["Ёлка"]);
    assert_eq!(titles("мои"), Vec::<String>::new(), "й is not и");
    assert_eq!(titles("  "), ["Ёлка", "Мой день"]);
    assert!(titles("ёлка ничего").is_empty(), "every word must match");
}

#[test]
fn torn_tail_is_repaired() {
    let f = Fixture::new();
    create(&f.open(), "pw").lock().unwrap();
    let mut bytes = fs::read(&f.path).unwrap();
    bytes.extend_from_slice(&[0; 100]);
    fs::write(&f.path, bytes).unwrap();
    assert!(unlock(&f.open(), "pw").is_some());
    assert_eq!(f.len() % BLOCK_SIZE as u64, 0);
}

#[test]
fn diary_date_moves_and_survives_reopen() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let day = s.save(None, EntryKind::Diary, None, "day".into(), "".into()).unwrap();
    let note = s.save(None, EntryKind::Note, None, "note".into(), "".into()).unwrap();
    s.set_diary_date(day.clone(), 19_000).unwrap();
    s.set_diary_date(note.clone(), 19_000).unwrap();
    assert!(s.set_diary_date(day.clone(), -1).is_err());
    s.lock().unwrap();
    let s = unlock(&c, "pw").unwrap();
    assert_eq!(s.entry(day).unwrap().unwrap().diary_date, Some(19_000));
    assert_eq!(s.entry(note).unwrap().unwrap().diary_date, None, "only diary entries have a date");
}

#[test]
fn previews_drop_block_markers_and_keep_inline_styles() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let preview = |body: &str| {
        let id = s.save(None, EntryKind::Note, None, "".into(), body.into()).unwrap();
        s.entries().unwrap().into_iter().find(|e| e.entry_id == id).unwrap().preview
    };
    // Headings and inline styles stay for the app to draw.
    assert_eq!(preview("# Liste\n**Brot** und *Käse*"), "# Liste\n**Brot** und *Käse*");
    assert_eq!(preview("> quote\n---\n```\ncode\n***"), "quote\ncode");
    assert_eq!(preview("- one\n  * two\n- [ ] open\n- [x] done"), "• one\n  • two\n☐ open\n☑ done");
    assert_eq!(preview("1. first\n*italic* line\n2 * 3 * 4"), "1. first\n*italic* line\n2 * 3 * 4");
    assert_eq!(preview("| a | **b** |\n|---|:-:|\n| 1 |  |\nx | y"), "a · **b**\n1 · \nx | y");
}

#[test]
fn note_links_point_at_live_entries_and_previews_show_link_text() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let a = s.save(None, EntryKind::Note, None, "A".into(), "".into()).unwrap();
    let b = s.save(None, EntryKind::Note, None, "B".into(), "".into()).unwrap();
    let gone = "0".repeat(32);
    let body = format!("see [B](note:{b}) and [B again](note:{b}), [self](note:{a}), [x](note:{gone}), [web](https://x.y)");
    s.save(Some(a.clone()), EntryKind::Note, None, "A".into(), body).unwrap();
    assert_eq!(s.note_links().unwrap(), vec![NoteLink { from_id: a.clone(), to_id: b.clone() }]);
    let preview = s.entries().unwrap().into_iter().find(|e| e.entry_id == a).unwrap().preview;
    assert_eq!(preview, "see B and B again, self, x, web");
    s.trash(vec![b]).unwrap();
    assert!(s.note_links().unwrap().is_empty(), "no links into the trash");
}

#[test]
fn tags_rename_merge_delete_and_folders_keep_their_order() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let a = s.save(None, EntryKind::Note, None, "a".into(), "".into()).unwrap();
    let b = s.save(None, EntryKind::Note, None, "b".into(), "".into()).unwrap();
    s.set_tags(a.clone(), vec!["work".into(), "home".into()]).unwrap();
    s.set_tags(b.clone(), vec!["Home".into()]).unwrap();
    let names = |s: &Session| s.tags().unwrap().into_iter().map(|t| (t.name, t.count)).collect::<Vec<_>>();
    assert_eq!(names(&s), [("home".to_string(), 2), ("work".to_string(), 1)]);
    s.rename_tag("home".into(), "Home".into()).unwrap(); // only the spelling changes
    assert_eq!(names(&s), [("Home".to_string(), 2), ("work".to_string(), 1)]);
    s.rename_tag("work".into(), "home".into()).unwrap(); // onto an existing tag: merged
    assert_eq!(names(&s), [("Home".to_string(), 2)]);
    assert_eq!(s.entry(a.clone()).unwrap().unwrap().tags, vec!["Home".to_string()]);
    s.delete_tag("HOME".into()).unwrap();
    assert!(s.tags().unwrap().is_empty());
    assert!(s.rename_tag("x".into(), "  ".into()).is_err());

    let x = s.create_folder("x".into()).unwrap();
    let y = s.create_folder("y".into()).unwrap();
    let z = s.create_folder("z".into()).unwrap();
    let order = |s: &Session| s.folders().unwrap().into_iter().map(|f| f.name).collect::<Vec<_>>();
    assert_eq!(order(&s), ["x", "y", "z"]);
    s.reorder_folders(vec![z, x, y]).unwrap();
    s.lock().unwrap();
    assert_eq!(order(&unlock(&c, "pw").unwrap()), ["z", "x", "y"]);
}

// ---- Export, import, backup (spec §11.7) -----------------------------------------------

fn path_of(f: &Fixture, name: &str) -> String {
    f.path.with_file_name(name).to_string_lossy().into()
}

#[test]
fn exported_vault_opens_alone_with_fresh_keys_and_imports_elsewhere() {
    let f = Fixture::new();
    let c = f.open();
    create(&c, "other").lock().unwrap();
    let s = create(&c, "pw");
    let folder = s.create_folder("Work".into()).unwrap();
    let note = s.save(None, EntryKind::Note, Some(folder.clone()), "plan".into(), "".into()).unwrap();
    let photo: Vec<u8> = (0..9_000u32).map(|i| (i % 253) as u8).collect();
    let a = s.add_attachment(note.clone(), photo.clone(), "image/jpeg".into(), Some(vec![7; 50]), 0, 4, 3).unwrap();
    let body = format!("intro\n![left s](att:{})\nmore", a.attachment_id);
    s.save(Some(note.clone()), EntryKind::Note, None, "plan".into(), body).unwrap();
    s.set_tags(note.clone(), vec!["Ideas".into()]).unwrap();
    s.set_pinned(vec![note.clone()], true).unwrap();
    let todo = s.save(None, EntryKind::Todo, None, "call".into(), "".into()).unwrap();
    s.set_done(todo.clone(), true).unwrap();
    s.save(None, EntryKind::Diary, None, "day".into(), "dear diary".into()).unwrap();

    let exported = path_of(&f, "export.bin");
    assert_eq!(c.export_vault(s.clone(), exported.clone(), "exp".into()).unwrap(), 4); // folder + 3 entries
    assert!(c.export_vault(s.clone(), exported.clone(), "exp".into()).is_err(), "never overwrites");
    let original = locators_of(&c, &f.path, "pw");
    s.lock().unwrap();

    let x = Container::open_or_create(exported.clone()).unwrap();
    assert!(unlock(&x, "pw").is_none() && unlock(&x, "other").is_none(), "only the export password");
    assert!(blocks_of(Path::new(&exported)).iter().all(|b| !owned(b, &original)), "fresh vault key");
    let check = |s: &Session| {
        let e = s.entry(note.clone()).unwrap().unwrap();
        assert_eq!((e.title.as_str(), e.pinned, e.folder_id.as_deref()), ("plan", true, Some(folder.as_str())));
        assert_eq!(e.tags, vec!["Ideas".to_string()]);
        assert_eq!(e.attachments.len(), 1);
        let id = &e.attachments[0].attachment_id;
        assert_ne!(id, &a.attachment_id, "attachment re-keyed");
        assert_eq!(e.body, format!("intro\n![left s](att:{id})\nmore"), "embed follows the new id");
        assert_eq!(s.attachment_data(id.clone()).unwrap(), photo);
        assert_eq!(s.thumbnail_data(id.clone()).unwrap(), Some(vec![7; 50]));
        assert!(s.entry(todo.clone()).unwrap().unwrap().done);
        assert_eq!(s.folders().unwrap().len(), 1);
        assert_eq!(s.entries().unwrap().len(), 3);
    };
    let xs = unlock(&x, "exp").unwrap();
    check(&xs);
    xs.lock().unwrap();
    drop(x);

    let g = Fixture::new();
    let other = g.open();
    let copy = path_of(&g, "incoming.bin");
    fs::copy(&exported, &copy).unwrap();
    assert_eq!(other.import_vault(copy.clone(), "nope".into(), None).unwrap(), ImportResult::NotFound);
    assert_eq!(other.import_vault(copy.clone(), "exp".into(), None).unwrap(), ImportResult::Created { entries: 4 });
    let s = unlock(&other, "exp").unwrap();
    check(&s);
    assert_eq!(other.import_vault(copy, "exp".into(), Some(s.clone())).unwrap(), ImportResult::Merged { entries: 0 });
    check(&s);
}

#[test]
fn import_merges_newer_entries_into_the_open_vault() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let one = s.save(None, EntryKind::Note, None, "one".into(), "".into()).unwrap();
    let two = s.save(None, EntryKind::Note, None, "two".into(), "".into()).unwrap();
    let gone = s.save(None, EntryKind::Note, None, "gone".into(), "".into()).unwrap();
    let exported = path_of(&f, "export.bin");
    c.export_vault(s.clone(), exported.clone(), "pw".into()).unwrap();

    // Both sides change after the export.
    s.save(Some(one.clone()), EntryKind::Note, None, "one, edited here".into(), "".into()).unwrap();
    s.delete(gone.clone()).unwrap();
    let x = Container::open_or_create(exported.clone()).unwrap();
    let xs = unlock(&x, "pw").unwrap();
    xs.save(Some(two.clone()), EntryKind::Note, None, "two, edited there".into(), "".into()).unwrap();
    xs.save(None, EntryKind::Note, None, "three".into(), "".into()).unwrap();
    xs.lock().unwrap();
    drop(x);

    let before = c.block_count().unwrap();
    assert_eq!(c.import_vault(exported.clone(), "pw".into(), Some(s.clone())).unwrap(), ImportResult::Merged { entries: 2 });
    let mut t = titles(&s);
    t.sort();
    assert_eq!(t, ["one, edited here", "three", "two, edited there"], "newer wins, later delete holds");
    assert!(c.block_count().unwrap() > before);
    assert_eq!(c.import_vault(exported, "pw".into(), Some(s.clone())).unwrap(), ImportResult::Merged { entries: 0 });
    s.lock().unwrap();
    let mut t = titles(&unlock(&c, "pw").unwrap());
    t.sort();
    assert_eq!(t, ["one, edited here", "three", "two, edited there"]);
}

#[test]
fn backup_restores_every_vault_and_rejects_other_files() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    let backup = path_of(&f, "backup.bin");
    c.backup_to(backup.clone()).unwrap();
    assert_eq!(fs::read(&backup).unwrap(), fs::read(&f.path).unwrap());
    let s = unlock(&c, "R").unwrap();
    s.save(None, EntryKind::Note, None, "after backup".into(), "".into()).unwrap();
    s.lock().unwrap();
    drop(c);

    let here: String = f.path.to_string_lossy().into();
    let junk = path_of(&f, "junk.bin");
    fs::write(&junk, [0u8; 100]).unwrap();
    assert!(restore_container(here.clone(), junk, "R".into()).is_err());
    // The right shape is not enough: random bytes, or a password that opens nothing, change nothing.
    let noise = path_of(&f, "noise.bin");
    fs::write(&noise, crate::crypto::random_bytes(8 * BLOCK_SIZE)).unwrap();
    let before = fs::read(&f.path).unwrap();
    assert!(!restore_container(here.clone(), noise, "R".into()).unwrap());
    assert!(!restore_container(here.clone(), backup.clone(), "nope".into()).unwrap());
    assert_eq!(fs::read(&f.path).unwrap(), before);
    // Any vault's password will do.
    assert!(restore_container(here, backup, "D".into()).unwrap());
    let c = f.open();
    assert_eq!(titles(&unlock(&c, "R").unwrap()), ["secret"]);
    assert_eq!(titles(&unlock(&c, "D").unwrap()), ["groceries"]);
}

#[test]
fn revoked_password_stays_revoked_when_its_block_comes_back() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    // The old KEYWRAP's bytes, as an old copy of the file or a merge would bring them back.
    let old: Vec<Vec<u8>> = {
        let salt = Heap::new(&f.path).read_salt().unwrap();
        let pk = PasswordKeys::derive("R", &salt).unwrap();
        blocks_of(&f.path).into_iter().filter(|b| locator::matches(&pk.loc, &b[..NONCE_LEN])).collect()
    };
    assert_eq!(old.len(), 1);
    let s = unlock(&c, "R").unwrap();
    assert_eq!(c.change_password(s.clone(), "R".into(), "R2".into()).unwrap(), ChangePasswordResult::Changed);
    s.lock().unwrap();
    drop(c);

    Heap::new(&f.path).append(&old).unwrap();
    let c = f.open();
    let resurrected = c.block_count().unwrap() - 1;
    assert!(unlock(&c, "R").is_none(), "a revoked KEYWRAP must not open the vault");
    // ...and it was erased on the way.
    assert_ne!(Heap::new(&f.path).read_block(resurrected).unwrap(), old[0]);
    assert_eq!(titles(&unlock(&c, "R2").unwrap()), ["secret"]);
    // Changing back to the old password works: the new KEYWRAP is not the revoked one.
    let s = unlock(&c, "R2").unwrap();
    assert_eq!(c.change_password(s.clone(), "R2".into(), "R".into()).unwrap(), ChangePasswordResult::Changed);
    s.lock().unwrap();
    assert_eq!(titles(&unlock(&c, "R").unwrap()), ["secret"]);
}

#[test]
fn imported_attachments_get_seeds_from_their_content() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let a = s.save(None, EntryKind::Note, None, "a".into(), "".into()).unwrap();
    s.add_attachment(a.clone(), vec![1; 5000], "image/jpeg".into(), None, 0, 1, 1).unwrap();
    let b = s.save(None, EntryKind::Note, None, "b".into(), "".into()).unwrap();
    s.add_attachment(b.clone(), vec![2; 9000], "image/jpeg".into(), None, 0, 1, 1).unwrap();
    // The same content twice is stored once.
    let d = s.save(None, EntryKind::Note, None, "d".into(), "".into()).unwrap();
    s.add_attachment(d.clone(), vec![1; 5000], "image/jpeg".into(), None, 0, 1, 1).unwrap();
    let exported = path_of(&f, "export.bin");
    c.export_vault(s.clone(), exported.clone(), "x".into()).unwrap();
    s.lock().unwrap();

    let x = Container::open_or_create(exported).unwrap();
    let xs = unlock(&x, "x").unwrap();
    let att = |id: &str| xs.entry(id.into()).unwrap().unwrap().attachments.remove(0);
    let (ia, ib, id) = (att(&a), att(&b), att(&d));
    assert_ne!(ia.attachment_id, ib.attachment_id, "two contents, two seeds");
    assert_eq!(ia.attachment_id, id.attachment_id, "one content, one seed");
    assert_eq!(xs.attachment_data(ia.attachment_id).unwrap(), vec![1; 5000]);
    assert_eq!(xs.attachment_data(ib.attachment_id).unwrap(), vec![2; 9000]);
}

// ---- Convergence (spec §12 T7) -----------------------------------------------------------

/// Everything a vault shows, by entry id: title, body, a concurrent version if any.
fn snapshot(c: &Container, pw: &str) -> Vec<(String, String, String, Option<String>)> {
    let s = unlock(c, pw).expect("vault opens");
    let mut out: Vec<_> = s
        .entries()
        .unwrap()
        .into_iter()
        .map(|e| {
            let d = s.entry(e.entry_id.clone()).unwrap().unwrap();
            (e.entry_id, d.title, d.body, d.conflict_body)
        })
        .collect();
    s.lock().unwrap();
    out.sort();
    out
}

/// A device: its own copy of the file.
fn device(f: &Fixture, name: &str) -> (String, Arc<Container>) {
    let p = path_of(f, name);
    fs::copy(&f.path, &p).unwrap();
    let c = Container::open_or_create(p.clone()).unwrap();
    (p, c)
}

#[test]
fn merges_in_any_order_give_the_same_state() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let shared = s.save(None, EntryKind::Note, None, "shared".into(), "v0".into()).unwrap();
    let doomed = s.save(None, EntryKind::Note, None, "doomed".into(), "".into()).unwrap();
    s.lock().unwrap();
    drop(c);

    // Three devices edit their copies apart: the same entry concurrently, a delete, new ones.
    let (pa, a) = device(&f, "a.bin");
    let (pb, b) = device(&f, "b.bin");
    let (pc, cc) = device(&f, "c.bin");
    let sa = unlock(&a, "pw").unwrap();
    sa.save(Some(shared.clone()), EntryKind::Note, None, "shared".into(), "from a".into()).unwrap();
    sa.save(None, EntryKind::Note, None, "only a".into(), "".into()).unwrap();
    sa.lock().unwrap();
    let sb = unlock(&b, "pw").unwrap();
    sb.save(Some(shared.clone()), EntryKind::Note, None, "shared".into(), "from b".into()).unwrap();
    sb.delete(doomed.clone()).unwrap();
    sb.lock().unwrap();
    let sc = unlock(&cc, "pw").unwrap();
    sc.save(None, EntryKind::Diary, None, "only c".into(), "".into()).unwrap();
    sc.lock().unwrap();

    // Every device takes what the other two had, each in its own order.
    let (a0, b0, c0) = (path_of(&f, "a0.bin"), path_of(&f, "b0.bin"), path_of(&f, "c0.bin"));
    for (from, to) in [(&pa, &a0), (&pb, &b0), (&pc, &c0)] {
        fs::copy(from, to).unwrap();
    }
    for (dev, first, second) in [(&a, &b0, &c0), (&b, &c0, &a0), (&cc, &a0, &b0)] {
        dev.merge_from(first.clone()).unwrap();
        dev.merge_from(second.clone()).unwrap();
    }
    let state = snapshot(&a, "pw");
    assert_eq!(snapshot(&b, "pw"), state);
    assert_eq!(snapshot(&cc, "pw"), state);

    let titles: Vec<&str> = state.iter().map(|e| e.1.as_str()).collect();
    assert!(!titles.contains(&"doomed"), "the delete holds");
    for t in ["only a", "only c", "shared"] {
        assert!(titles.contains(&t), "{t} is there");
    }
    // Two concurrent edits: one is shown, the other offered as a conflict.
    let s = state.iter().find(|e| e.0 == shared).unwrap();
    let mut both = [s.2.clone(), s.3.clone().expect("a conflict")];
    both.sort();
    assert_eq!(both, ["from a".to_string(), "from b".to_string()]);

    // The same copy again adds nothing; B's file as it is now (with chaff and PADs from the
    // sessions above) adds blocks but changes nothing visible.
    assert_eq!(a.merge_from(b0).unwrap(), 0);
    a.merge_from(pb.clone()).unwrap();
    assert_eq!(snapshot(&a, "pw"), state);
}

#[test]
fn a_purged_revision_brought_back_by_a_merge_stays_dead() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let id = s.save(None, EntryKind::Note, None, "note".into(), "old text".into()).unwrap();
    s.lock().unwrap();
    let old_copy = path_of(&f, "old.bin");
    c.backup_to(old_copy.clone()).unwrap();
    let s = unlock(&c, "pw").unwrap();
    s.save(Some(id.clone()), EntryKind::Note, None, "note".into(), "new text".into()).unwrap(); // purges "old text"
    s.lock().unwrap();

    assert!(c.merge_from(old_copy.clone()).unwrap() > 0, "the old revision's blocks come back");
    let state = snapshot(&c, "pw");
    assert_eq!(state.len(), 1);
    assert_eq!((state[0].2.as_str(), state[0].3.as_deref()), ("new text", None));
    // Unlocking erased them again: the old text is nowhere in the file in clear or decryptable.
    let s = unlock(&c, "pw").unwrap();
    assert_eq!(s.pending_purge_blocks().unwrap(), 0);
    s.lock().unwrap();
}

#[test]
fn an_edit_resolves_a_conflict_for_good() {
    let f = Fixture::new();
    let c = f.open();
    let s = create(&c, "pw");
    let id = s.save(None, EntryKind::Note, None, "n".into(), "base".into()).unwrap();
    s.lock().unwrap();
    let (pb, b) = device(&f, "b.bin");
    let sb = unlock(&b, "pw").unwrap();
    sb.save(Some(id.clone()), EntryKind::Note, None, "n".into(), "there".into()).unwrap();
    sb.lock().unwrap();
    let s = unlock(&c, "pw").unwrap();
    s.save(Some(id.clone()), EntryKind::Note, None, "n".into(), "here".into()).unwrap();
    s.lock().unwrap();

    c.merge_from(pb.clone()).unwrap();
    assert!(snapshot(&c, "pw")[0].3.is_some(), "a conflict after the merge");
    let s = unlock(&c, "pw").unwrap();
    s.save(Some(id.clone()), EntryKind::Note, None, "n".into(), "here and there".into()).unwrap();
    s.lock().unwrap();
    c.merge_from(pb).unwrap();
    let state = snapshot(&c, "pw");
    assert_eq!((state[0].2.as_str(), state[0].3.as_deref()), ("here and there", None));
}

#[test]
fn a_merge_does_not_give_an_old_password_back() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    let old_copy = path_of(&f, "old.bin");
    c.backup_to(old_copy.clone()).unwrap();
    let s = unlock(&c, "R").unwrap();
    c.change_password(s.clone(), "R".into(), "R2".into()).unwrap();
    s.lock().unwrap();
    assert!(c.merge_from(old_copy).unwrap() > 0);
    assert!(unlock(&c, "R").is_none());
    assert_eq!(titles(&unlock(&c, "R2").unwrap()), ["secret"]);
    assert_eq!(titles(&unlock(&c, "D").unwrap()), ["groceries"]);
}

#[test]
fn two_vaults_under_one_password_after_a_merge_become_one() {
    let f = Fixture::new();
    f.open();
    // Two devices start from one container and each makes a vault with the same password.
    let (pa, a) = device(&f, "a.bin");
    let (pb, b) = device(&f, "b.bin");
    let sa = create(&a, "same");
    sa.save(None, EntryKind::Note, None, "written on a".into(), "".into()).unwrap();
    sa.lock().unwrap();
    let sb = create(&b, "same");
    sb.save(None, EntryKind::Note, None, "written on b".into(), "".into()).unwrap();
    sb.lock().unwrap();
    let (a0, b0) = (path_of(&f, "a0.bin"), path_of(&f, "b0.bin"));
    fs::copy(&pa, &a0).unwrap();
    fs::copy(&pb, &b0).unwrap();
    a.merge_from(b0.clone()).unwrap();
    b.merge_from(a0).unwrap();

    let both = ["written on a", "written on b"];
    let sorted = |c: &Container| {
        let mut t = titles(&unlock(c, "same").unwrap());
        t.sort();
        t
    };
    assert_eq!(sorted(&a), both);
    assert_eq!(sorted(&b), both, "both devices keep the same vault");
    // Done once: the password now opens a single vault, and nothing is copied twice.
    let salt = Heap::new(&pa).read_salt().unwrap();
    let pk = PasswordKeys::derive("same", &salt).unwrap();
    assert_eq!(blocks_of(Path::new(&pa)).iter().filter(|b| locator::matches(&pk.loc, &b[..NONCE_LEN])).count(), 1);
    a.merge_from(b0).unwrap(); // the other KEYWRAP comes back once more
    assert_eq!(sorted(&a), both);
    assert_eq!(snapshot(&a, "same").iter().map(|e| &e.0).collect::<Vec<_>>(), snapshot(&b, "same").iter().map(|e| &e.0).collect::<Vec<_>>());
}

#[test]
fn an_open_session_sees_merged_entries_after_reload() {
    let f = Fixture::new();
    let c = f.open();
    create(&c, "pw").lock().unwrap();
    let (pb, b) = device(&f, "b.bin");
    let sb = unlock(&b, "pw").unwrap();
    sb.save(None, EntryKind::Note, None, "from b".into(), "".into()).unwrap();
    sb.lock().unwrap();

    let s = unlock(&c, "pw").unwrap();
    s.save(None, EntryKind::Note, None, "here".into(), "".into()).unwrap();
    c.merge_from(pb).unwrap();
    assert_eq!(titles(&s), ["here"], "not before the reload");
    s.reload().unwrap();
    let mut t = titles(&s);
    t.sort();
    assert_eq!(t, ["from b", "here"]);
    // The session goes on: it can still write, and everything survives a reopen.
    s.save(None, EntryKind::Note, None, "after".into(), "".into()).unwrap();
    s.lock().unwrap();
    assert!(s.reload().is_err(), "a locked session cannot reload");
    let mut t = titles(&unlock(&c, "pw").unwrap());
    t.sort();
    assert_eq!(t, ["after", "from b", "here"]);
}

#[test]
fn sync_target_lives_in_the_vault() {
    let f = Fixture::new();
    let c = f.open();
    two_vaults(&c);
    let d = unlock(&c, "D").unwrap();
    assert_eq!(d.sync_uri().unwrap(), None);
    d.set_sync_uri(Some("content://drive/doc/1".into())).unwrap();
    d.lock().unwrap();
    assert_eq!(unlock(&c, "D").unwrap().sync_uri().unwrap().as_deref(), Some("content://drive/doc/1"));
    assert_eq!(unlock(&c, "R").unwrap().sync_uri().unwrap(), None, "another vault knows nothing of it");
    let d = unlock(&c, "D").unwrap();
    d.set_sync_uri(None).unwrap();
    assert_eq!(d.sync_uri().unwrap(), None);
}

#[test]
fn merge_refuses_another_container() {
    let f = Fixture::new();
    let c = f.open();
    let g = Fixture::new();
    g.open();
    assert!(c.merge_from(g.path.to_string_lossy().into()).is_err());
}

// ---- Indistinguishability (single snapshot) --------------------------------------------

/// Every locator a vault owns: its keywrap's password locator, meta, blob and PAD.
fn locators_of(c: &Container, path: &Path, pw: &str) -> Vec<Key> {
    let salt = Heap::new(path).read_salt().unwrap();
    let pk = PasswordKeys::derive(pw, &salt).unwrap();
    let s = unlock(c, pw).unwrap();
    let mut out = vec![pk.loc.duplicate()];
    {
        let inner = s.inner.lock().unwrap();
        let keys = inner.keys().unwrap();
        out.extend(keys.own_locators().iter().map(|k| k.duplicate()));
    }
    s.lock().unwrap();
    out
}

fn blocks_of(path: &Path) -> Vec<Vec<u8>> {
    let h = Heap::new(path);
    (0..h.block_count().unwrap()).map(|i| h.read_block(i).unwrap()).collect()
}

fn owned(block: &[u8], locs: &[Key]) -> bool {
    locs.iter().any(|k| locator::matches(k, &block[..NONCE_LEN]))
}

/// Short entries maximise zero padding; edits leave PADs; deletes leave tombstones.
fn fill(c: &Container, pw: &str, entries: usize) {
    let s = create(c, pw);
    for i in 0..entries {
        let id = s.save(None, EntryKind::Note, None, format!("entry {i}"), format!("short text {i}")).unwrap();
        if i % 3 == 0 {
            s.save(Some(id.clone()), EntryKind::Note, None, format!("entry {i}"), format!("edited {i}")).unwrap();
        }
        if i % 10 == 0 {
            s.delete(id).unwrap();
        }
    }
    s.lock().unwrap();
}

fn concat(set: &[Vec<u8>], range: std::ops::Range<usize>) -> Vec<u8> {
    set.iter().flat_map(|b| b[range.clone()].iter().copied()).collect()
}

fn histogram(bytes: &[u8]) -> [f64; 256] {
    let mut h = [0f64; 256];
    for &b in bytes {
        h[b as usize] += 1.0;
    }
    h
}

/// Normal approximation (Wilson–Hilferty) of a χ²(255) statistic.
fn z_of(chi2: f64) -> f64 {
    let k = 255.0;
    let v = 2.0 / (9.0 * k);
    ((chi2 / k).powf(1.0 / 3.0) - (1.0 - v)) / v.sqrt()
}

fn uniform_z(bytes: &[u8]) -> f64 {
    let h = histogram(bytes);
    let e = bytes.len() as f64 / 256.0;
    z_of(h.iter().map(|o| (o - e).powi(2) / e).sum())
}

/// χ² homogeneity on a 2×256 table.
fn same_z(a: &[u8], b: &[u8]) -> f64 {
    let (ha, hb) = (histogram(a), histogram(b));
    let (na, nb) = (a.len() as f64, b.len() as f64);
    let mut chi2 = 0.0;
    for i in 0..256 {
        let col = ha[i] + hb[i];
        if col == 0.0 {
            continue;
        }
        let (ea, eb) = (col * na / (na + nb), col * nb / (na + nb));
        chi2 += (ha[i] - ea).powi(2) / ea + (hb[i] - eb).powi(2) / eb;
    }
    z_of(chi2)
}

fn compress_ratio(data: &[u8]) -> f64 {
    use std::io::Write;
    let mut e = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
    e.write_all(data).unwrap();
    e.finish().unwrap().len() as f64 / data.len() as f64
}

/// From the decoy holder's point of view, a hidden vault's blocks must look like chaff, and a
/// two-vault container's unknown blocks like a one-vault one's. Real cipher output is uniform
/// by construction, so this guards the implementation (headers, padding, locators, PADs).
/// Thresholds |z| < 5: about 6·10⁻⁷ false alarms per check.
#[test]
fn hidden_vault_is_indistinguishable_from_chaff() {
    let one = Fixture::new();
    let c1 = one.open();
    fill(&c1, "decoy", 120);
    let decoy1 = locators_of(&c1, &one.path, "decoy");
    let unknown_one: Vec<Vec<u8>> = blocks_of(&one.path).into_iter().filter(|b| !owned(b, &decoy1)).collect();

    let two = Fixture::new();
    let c2 = two.open();
    fill(&c2, "decoy", 120);
    fill(&c2, "hidden", 120);
    let decoy2 = locators_of(&c2, &two.path, "decoy");
    let hidden_locs = locators_of(&c2, &two.path, "hidden");
    let all = blocks_of(&two.path);
    let hidden: Vec<Vec<u8>> = all.iter().filter(|b| owned(b, &hidden_locs)).cloned().collect();
    let unknown_two: Vec<Vec<u8>> = all.iter().filter(|b| !owned(b, &decoy2)).cloned().collect();
    let chaff: Vec<Vec<u8>> = unknown_two.iter().filter(|b| !owned(b, &hidden_locs)).cloned().collect();
    assert!(hidden.len() >= 150 && chaff.len() >= 100, "hidden {} chaff {}", hidden.len(), chaff.len());

    let regions = [
        ("whole block", 0..BLOCK_SIZE),
        ("nonce random part", 0..R_LEN),
        ("locator", R_LEN..NONCE_LEN),
        ("ciphertext head", NONCE_LEN..NONCE_LEN + 64),
        ("ciphertext tail", BLOCK_SIZE - 1024..BLOCK_SIZE),
    ];
    for (name, r) in regions {
        for (set_name, set) in [("hidden", &hidden), ("chaff", &chaff), ("unknown 1", &unknown_one), ("unknown 2", &unknown_two)] {
            let z = uniform_z(&concat(set, r.clone()));
            assert!(z.abs() < 5.0, "{set_name}, {name}: z = {z:.2}");
        }
        let z = same_z(&concat(&hidden, r.clone()), &concat(&chaff, r.clone()));
        assert!(z.abs() < 5.0, "hidden vs chaff, {name}: z = {z:.2}");
        let z = same_z(&concat(&unknown_one, r.clone()), &concat(&unknown_two, r.clone()));
        assert!(z.abs() < 5.0, "unknown 1 vs 2, {name}: z = {z:.2}");
    }
    for (set_name, set) in [("hidden", &hidden), ("chaff", &chaff)] {
        let bytes = concat(set, 0..BLOCK_SIZE);
        let ratio = compress_ratio(&bytes);
        assert!(ratio > 0.99, "{set_name} compresses to {ratio}");
        let ones: u64 = bytes.iter().map(|b| b.count_ones() as u64).sum();
        let n = bytes.len() as f64 * 8.0;
        let z = (ones as f64 - n / 2.0) / (n / 4.0).sqrt();
        assert!(z.abs() < 5.0, "{set_name} monobit z = {z:.2}");
    }
}
