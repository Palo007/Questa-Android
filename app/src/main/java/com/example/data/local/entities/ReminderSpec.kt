package com.example.data.local.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * WP-8.2 (Wave 1): minimal typed stand-in for a PWA task reminder object (`TaskEntity.reminders`,
 * §5 card: "reminders: List<ReminderSpec>?"), sufficient for MIGRATION_6_7 to backfill the field
 * from `extraJson` (D9) and for it to round-trip through Room/kotlinx.serialization. WP-8.11
 * (Reminders and notifications, later wave) owns `ReminderEngine`/`ReminderEditorRules` and the
 * real once/weekly/daily/burn-rule behavior (PARITY_FIX_PLAN.html WP-8.11 card, app.js:6060-6082)
 * -- it may extend this class, but must not change the fields WP-8.2's migration already backfills
 * without a follow-up migration.
 *
 * Field shapes follow the WP-8.11 card text directly: `kind` is "once" | "weekly" | "daily";
 * `date` only meaningful for "once" (YYYY-MM-DD); `days` only meaningful for "weekly"/"daily"
 * (7 booleans, Sun=0..Sat=6, matching `TaskEntity.repeat`); `lastFiredKey` stays on the wire with
 * no `updatedAt` bump (D10).
 *
 * review r1 major 6: `id`/`time` are nullable with no required constructor argument, and `enabled`
 * defaults to `false` (Opti `reminderAppliesToday`, app.js:2083: `if (!r.enabled) return false;` --
 * a reminder missing its `enabled` key must never accidentally fire). `Converters.toReminderSpecList`
 * decodes each array element independently (one malformed element must not null out every other
 * reminder on the task) and, via `coerceInputValues`, tolerates a `null` for a defaulted field
 * (e.g. `lastFiredKey: null`) instead of throwing. `extraJson` is the standard T3.1
 * unknown-field-passthrough overlay: the verbatim original JSON of this one reminder element, so a
 * PWA field this class doesn't model (or doesn't model yet) still round-trips.
 *
 * review r2 major 1: `extraJson` is `@Transient` -- it must NEVER be part of this class's own
 * kotlinx.serialization encoding, or `Converters.fromReminderSpecList` would write it out as a
 * literal `"extraJson"` JSON key (a field Opti never has), and because that written text embeds
 * the PREVIOUS extraJson verbatim, the column would grow without bound on every
 * decode-then-encode cycle. `extraJson` is still a real Kotlin field, set/read manually by
 * `Converters` (via `.copy(extraJson = ...)` and `SerializationEngine.overlayJson`), just excluded
 * from the automatic (de)serializer.
 */
// WP-8.11: behavior lives in com.example.reminders (ReminderEngine, ReminderEditorRules). The fields
// and the wire order (Converters.fromReminderSpecList) are unchanged in meaning; a null now clears.
@Serializable
data class ReminderSpec(
    val id: String? = null,
    val time: String? = null,
    val enabled: Boolean = false,
    val kind: String = "daily",
    val date: String? = null,
    val days: List<Boolean>? = null,
    val lastFiredKey: String = "",
    @Transient val extraJson: String? = null,
    /**
     * P4 / KT-38 (DEC-3, match Opti app.js:2069-2072): the verbatim JSON of an array element that
     * does NOT decode as a reminder ("junk", {"time":5}, null, ...). Such an "opaque" entry has
     * `enabled = false` (never scheduled: ReminderEngine skips `!enabled`), and
     * `Converters.fromReminderSpecList` writes this text back unchanged at the same index, so the
     * element survives import, fire (in place) and upload like in Opti. `id`/`time`/`lastFiredKey`
     * are copied from the element only when they are JSON strings, for the editor's `_prevRem`
     * reads (app.js:6061-6072). A copy() of an opaque entry does not change what is written.
     * Not in Room as a column: it is rebuilt from the `reminders` JSON text on every read.
     */
    @Transient val raw: String? = null
) {
    /**
     * P4 review #6: an opaque element that is falsy in JS (null, false, 0, ""). Opti's editor
     * reads `_prevRem = (EDIT.reminders && EDIT.reminders[0]) || null` (app.js:6061), so such an
     * element counts as "no previous reminder". No backing field: never serialized.
     */
    val isFalsyOpaque: Boolean
        get() {
            val r = raw ?: return false
            return r == "null" || r == "false" || r == "\"\"" || r.toDoubleOrNull()?.let { it == 0.0 } == true
        }
}
