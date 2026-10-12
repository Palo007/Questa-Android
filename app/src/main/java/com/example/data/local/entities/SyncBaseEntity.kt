package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * WP-8.2 (Wave 1, schema v7) -- D8/D16: the Room home for the sync "base" (the last snapshot both
 * this device and the remote agree was uploaded), replacing the DataStore
 * `DROPBOX_BASE_SNAPSHOT`/`DROPBOX_LAST_REV` keys (§4.3). Single-row table (`id = 0`), bound to
 * `(appKey, accountId)` per D8 so a base is never silently reused across a different Dropbox
 * account/app-key pair -- [com.example.data.sync.BaseStore.validFor] is the guard.
 *
 * `json` is the frozen, already-canonicalized upload body (WP-8.3's `CanonicalJson`, D8) -- WP-8.2
 * stores and moves it byte for byte and never parses it.
 */
@Serializable
@Entity(tableName = "sync_base")
data class SyncBaseEntity(
    @PrimaryKey val id: Int = 0,
    val appKey: String,
    val accountId: String?,
    val rev: String,
    val json: String
)
