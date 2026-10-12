# CR-KT-017: task editor Save writes back only the fields the user changed

- `writeback_cases.json`: generated, do not edit. `git -C <Opti> show b7a94cd:app.js > app.js`, then `node gen_writeback.js app.js > writeback_cases.json`.
- The generator cuts the write-back block out of Opti app.js b7a94cd (saveTask, from `(function(){ const _eq` to `EDIT = orig;`) by text markers and runs it with `new Function`. No hand-copied logic.
- Each `cases[]` row: `base` (Opti `_base`, the task when the sheet opened), `live` (Opti `orig`, the stored task at Save), `sheet` (Opti `EDIT`, the sheet copy), `expected` (`orig` after the block), `expected_json` (`JSON.stringify(expected)`; key order = the live task's, new keys appended).
- Rule: a key whose sheet value differs from base (`JSON.stringify`, undefined = null) takes the sheet value; any other key keeps the live value; a key in base but not in the sheet is removed; when the checklist is written, live subtasks whose id was never in `base.checklist` (arrived by sync) are appended.
- Not in the block: `EDIT.updatedAt = now()` (after it) and the attachments reconcile (before it). Every case has no user image delta. Kotlin keeps the live attachments (CR-KT-015).
- Cases: sync_arrived_subtask, peer_counter_tap, cdown_only_peer_cup, untouched_repeat_habit, title_edit_only, field_removed_in_sheet, checklist_edit_plus_sync_subtask.
- Kotlin check: parse base/live/sheet with `SerializationEngine.parseTaskEntity`, run `TaskWriteBack.apply`, compare `CanonicalJson.stringify(buildTaskJson(result))` to `expected_json` byte for byte (Kotlin repo `fixtures/cr_kt_017/`, test `TaskWriteBackTest`).

Never edit after ACCEPTED (PROTOCOL P4).
