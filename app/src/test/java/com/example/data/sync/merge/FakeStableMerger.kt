package com.example.data.sync.merge

import kotlinx.serialization.json.JsonObject

/**
 * WP-8.4 test double for the WP-8.6 unit tests: returns `remote` when it is
 * stableStringify-equal to `local`, else `local`. Never emits conflict events.
 *
 * The contract places this in a test-fixtures source set; the build has none (Gradle is
 * WP-8.B's), so it lives in the unit-test source set, which 8.6's unit tests share.
 */
class FakeStableMerger : SnapshotMerger {
    override fun merge(base: JsonObject?, local: JsonObject, remote: JsonObject, ctx: MergeContext, sink: ConflictSink): JsonObject =
        if (Js.deepEqual(local, remote)) remote else local
}
