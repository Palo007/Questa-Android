package com.example.data.sync

import com.example.data.local.entities.RewardEntity
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review 45 C2/finding 4: an infinite reward cost must serialise, as Opti JSON.stringify does. */
class RewardCostWireTest {

    @Test fun `infinite cost goes on the wire as null`() {
        val r = RewardEntity(id = "r1", title = "Big", cost = Double.POSITIVE_INFINITY, createdAt = 1L, updatedAt = 1L)
        assertEquals(JsonNull, SerializationEngine.buildRewardJson(r)["cost"])
    }

    @Test fun `null cost reads back as the deferred 10 fallback`() {
        // [DEFERRED_OPEN_QUESTION] review 45 finding 1: Opti reads null (buys for 0). Locks today's value.
        val r = RewardEntity(id = "r1", title = "Big", cost = Double.POSITIVE_INFINITY, createdAt = 1L, updatedAt = 1L)
        assertEquals(10.0, SerializationEngine.parseRewardEntity(SerializationEngine.buildRewardJson(r)).cost, 0.0)
    }
}
