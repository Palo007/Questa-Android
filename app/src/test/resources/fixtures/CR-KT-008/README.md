# CR-KT-008 (KT-95): task.tags holds tag IDS, not names

- `task_with_tag_ids.json`:
  - `tags`: two tags exactly as Opti `addTag` writes them (app.js:3258-3260): `{id: uid(), name, color: TAG_COLORS[n % 10], createdAt: Date.now(), updatedAt: now()}`. `uid()` is `Date.now().toString(36) + 5 random base36 chars` (app.js:2021), so ids look like `mg99c0e8k3f7q`.
  - `pwaTask`: a todo as the PWA writes it. `tags` holds the tag id (Opti `addTagToEdit` / `toggleEditTag` push the id, app.js:3299-3303; `tagById` app.js:3256, `tagChips` app.js:3272 and the tag filter resolve ids only, so a name token shows no chip on the PWA).
  - `legacyNameTask`: a task as the pre-fix Kotlin editor wrote it: a known tag NAME (`work`) and an unknown token (`orphan-name`).
  - `expected_legacyNameTask_tags_after_editor_save`: an editor save converts known names to ids and keeps unknown tokens.
  - `expected_pwaTask_tags_after_bulk_remove_health` / `expected_legacyNameTask_tags_after_bulk_remove_work`: bulk remove takes ids and removes either spelling.
  - `expected_tags_after_bulk_add_work_to_both`: bulk add of the `work` id adds the id to the PWA task and adds nothing to the legacy task (it already holds the name; no duplicate, legacy token left as is).
- Hand-written from Opti 7d5d4f3 app.js (no PWA change needed: the PWA already writes ids).
- Never edit this file after ACCEPTED (PROTOCOL P4).
