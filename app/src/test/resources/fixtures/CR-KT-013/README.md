# CR-KT-013: local event pruning (Opti `pruneEvents`)

- `prune.json`: `now`, `ageLimitMs` (18*30*86400000), `cutoff` (= now - ageLimitMs), `productionCap` (200000) and 3 scenarios. Each scenario has its own `cap`, the input `events` (`id` = insert order / primary key, `uid`, `ts`, `kind`, `synthetic`), the ids pruned by the age pass and by the cap pass, and `expectedKeptIds` / `expectedKeptUids` (ordered by id ascending).
  - `age_only_production_cap`: cap 200000 is not reached. ts == cutoff is kept; ts == cutoff-1 is pruned; synthetic and `lifecycle` rows are pruned too; a future ts is kept.
  - `cap_after_age_small_cap`: test cap 5. The count is taken AFTER the age pass (7 rows, over = 2; a pre-age count would give 4). The cap deletes the oldest by ts (id 3 before id 2). ids 2 and 5 have the same ts at the cap edge: lower id 2 is deleted, 5 is kept.
  - `count_equals_cap_small_cap`: test cap 5; exactly 5 rows remain, nothing more is deleted.
- Rule: RE-PWA-011 (pwa.md:135-142), Opti app.js `pruneEvents` :2043-2074, constants :986-987, store keyPath `id` autoIncrement and `ts` index :1001-1002.
- Made by `gen_prune.js` (`node gen_prune.js > prune.json`). It simulates the IDB store and the two cursor passes in plain Node. It did NOT run Opti's code against a real IndexedDB. **PWA must verify this file** (for example by loading the events into a fake IDB and running `pruneEvents` with `Date.now` = `now` and `EVENT_HARD_CAP` = `cap`) before Kotlin closes the packet.
- [DEFERRED_OPEN_QUESTION] `event_logs` has no timestamp index in Kotlin; both deletes (age, cap) full-scan the table. Accepted because it runs once per process; adding an index is a schema change and needs its own CR.
- Cap 5 is a test override only. Production cap is 200000.
- Mirrored in the Kotlin repo at `app/src/test/resources/fixtures/CR-KT-013/`.
- Never edit this file after ACCEPTED (PROTOCOL P4).
