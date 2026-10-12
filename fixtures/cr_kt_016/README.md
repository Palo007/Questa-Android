# CR-KT-016: task editor Save writes the PWA `edit` event

- `edit_events.json`: generated, do not edit. `node gen_edit_events.js <Opti 027e371 app.js> > edit_events.json`.
- The generator cuts the code out of Opti app.js 027e371 by text markers and runs it: saveTask counter loops, saveTask change list (`_ch`), the new-task `create` event, and the feed `edit` renderer (`_summ` / `_detail`).
- Each `edit_cases[]` row: `orig` (task when the sheet opened), `edit` (sheet copy), `attachments` (`attUserDelta`), `upDelta` / `downDelta`, `event` (null = no event), `event_json` (`JSON.stringify` of the stored record: `ts, uid, dev` from logEvent, then the event), `feed_lines` (feed `desc` HTML: each `<div>` starts a line, tags removed, entities decoded; line 1 is the row text, the rest are the detail rows), `valueAfter` (habit `t.value` after the counter loops).
- Counter numbers come from the real loops with `t` = the edited record (its difficulty and value); character side effects are stubbed.
- `create_case`: `{kind:'create', taskType, taskId, taskTitle}`. The feed text `Created <type> <title>` is written by hand (one-line renderer).

## PWA defect found while generating (027e371)

saveTask runs `EDIT = orig;` (after the write-back) BEFORE the change list. `orig` and `EDIT` are then the same object, so the title / notes / difficulty / checklist / schedule / reminders tests are always equal and the PWA writes only the `attachments` and `counter` items today. The harness passes two objects (`orig` = `_base`, the sheet-open baseline; `EDIT` = the sheet copy), which is what the change-list code is written for. Fix on the PWA side: build `_ch` from `_base` vs the sheet copy, before `EDIT = orig`.

Never edit after ACCEPTED (PROTOCOL P4).
