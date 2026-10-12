# CR-KT-015: image attachments on tasks

- `task_attachments.json`:
  - `taskWithAttachments`: a todo with two images. New task key `attachments`: array of `{id, sha, mime, w, h, bytes, addedAt}`.
    - `id`: `uid()` (app.js:2021 style). `sha`: sha256 hex (lower case) of the full JPEG bytes. `mime`: always `image/jpeg` in v1. `w`/`h`: pixel size of the full image. `bytes`: full file size. `addedAt`: ms epoch.
    - Items never change after they are added. A user can only add or remove one.
  - `blobPaths`: where the image bytes live in Dropbox. Full image `/attachments/<sha>.jpg`, thumbnail `/attachments/<sha>_t.jpg`.
  - `merge_cases`: the `attachments` splice, run after the task winner is picked (like `mergeChecklist`, sync.js:1101). Union of local + remote ids. An id that is in base and missing on one side is removed. No base = pure union. Result order: `addedAt` ascending, then `id`.
- Hand-written against Opti ffdce91.
- Never edit this file after ACCEPTED (PROTOCOL P4).
