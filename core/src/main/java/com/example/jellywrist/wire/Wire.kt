package com.example.jellywrist.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Phone↔watch companion protocol over the Wearable Data Layer.
 *
 * Message paths are versioned (`/v1`): an incompatible payload change creates a `/v2`
 * path rather than mutating the existing payload — a peer that does not understand
 * version `v2` silently ignores it and the sender falls back to `v1` (where relevant).
 * The arrow comments indicate the direction and the DTO carried.
 */
object WirePaths {
    /** Capability published by the watch (wear.xml) — the phone discovers it via CapabilityClient. */
    const val CAPABILITY_WATCH = "jellywrist_watch"

    /** phone → watch: [SetupRequest]. Request to provision the watch. */
    const val SETUP = "/jellywrist/setup/v1"

    /** watch → phone: [SetupQcCode]. The watch initiated a Quick Connect and publishes the code. */
    const val SETUP_QC = "/jellywrist/setup/qc/v1"

    /** watch → phone: [SetupResult]. Provisioning result (success or error). */
    const val SETUP_RESULT = "/jellywrist/setup/result/v1"

    /** phone → watch: empty payload. Request for the watch's current status. */
    const val STATUS_REQUEST = "/jellywrist/status/v1"

    /** watch → phone: [WatchStatus]. Complete state of the watch (config, collections, storage). */
    const val STATUS_REPLY = "/jellywrist/status/reply/v1"

    /** phone → watch: [PinCommand]. Pins/unpins a collection on the watch. */
    const val PIN = "/jellywrist/pin/v1"

    /** watch → phone: [WireAck]. ACK of the pin/unpin (sent before the network refresh). */
    const val PIN_REPLY = "/jellywrist/pin/reply/v1"

    /** phone → watch: [SyncCommand]. Empty payload remains compatible and means no metered consent. */
    const val SYNC_NOW = "/jellywrist/sync/v1"

    /** watch → phone: [WireAck]. ACK of the sync request. */
    const val SYNC_REPLY = "/jellywrist/sync/reply/v1"
}

/**
 * Error codes carried by [SetupResult.error] and [WireAck.error].
 *
 * Stable strings (_wire_) — changing a value breaks binary compatibility with an
 * older peer: prefer adding a new code over mutating an existing one.
 */
object WireErrors {
    const val INVALID_URL = "invalid_url"
    const val UNREACHABLE = "unreachable"
    const val QUICKCONNECT_UNAVAILABLE = "quickconnect_unavailable"
    const val EXPIRED = "expired"
    const val AUTH_FAILED = "auth_failed"
    const val NO_SESSION = "no_session"
    const val WATCH_UNREACHABLE = "watch_unreachable"
    const val BAD_COMMAND = "bad_command"

    /**
     * Unexpected watch failure during provisioning.
     *
     * Last line of defense: the watch must reply with something, even when it does not
     * know what. An older peer that does not know this code falls back to its generic
     * message — both sides already have an `else` branch.
     */
    const val INTERNAL = "internal"

    /** Local to the phone: no reply from the watch within the allotted time. */
    const val TIMEOUT = "timeout"
}

/** Stable blockers surfaced in [WatchStatus.syncBlocker]. */
object WireSyncBlockers {
    const val METERED_CONFIRMATION_REQUIRED = "metered_confirmation_required"
    const val LOW_BATTERY_CONFIRMATION_REQUIRED = "low_battery_confirmation_required"
}

/**
 * JSON for the wire protocol.
 *
 * `ignoreUnknownKeys`: a newer peer that adds a field does not break an older peer
 * (robustness first). `encodeDefaults`: default values are serialized — the recipient
 * does not have to reconstruct omitted fields.
 */
val WireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ── @Serializable DTOs ──────────────────────────────────────────────────────────────

/**
 * Provisioning request sent by the phone to the watch.
 *
 * [serverId] and [userId] describe the **requested** configuration: the watch compares them
 * to its session to distinguish a simple acknowledgement ("already configured") from a
 * reconfiguration to another server or another account. Null by default — a phone predating
 * their introduction does not fill them in; the watch then falls back to URL comparison.
 */
@Serializable
data class SetupRequest(
    val serverUrl: String,
    /** SHA-256 fingerprint of a self-signed certificate accepted on the phone (opt-in, DEC-006). */
    val pinnedCertSha256: String? = null,
    /**
     * The phone's `AuthSession.serverId` — stable identity of the server.
     *
     * Takes precedence over [serverUrl]: two distinct canonical URLs can designate the same
     * server (`https://jelly.lan` on the phone, `https://jelly.lan:8096` on the watch), and a
     * URL comparison would then wrongly trigger a destructive reconfiguration.
     */
    val serverId: String? = null,
    /** The phone's `AuthSession.userId` — the same server with another account is still a change. */
    val userId: String? = null,
)

/** Quick Connect code published by the watch — the phone auto-approves it. */
@Serializable
data class SetupQcCode(val code: String)

/** Result of provisioning the watch. */
@Serializable
data class SetupResult(
    val ok: Boolean,
    val userName: String? = null,
    /** `true` if the watch was already configured (existing session) — no action taken. */
    val alreadyConfigured: Boolean = false,
    /** [WireErrors] code on failure. */
    val error: String? = null,
)

/** Collection pinned on the watch (exposed in the status). */
@Serializable
data class WirePinnedCollection(
    val id: String,
    val name: String,
    /** `CollectionType.name` on the watch side ("PLAYLIST", "ALBUM", "ARTIST"). */
    val type: String,
    val trackCount: Int,
    val estimatedBytes: Long,
    val lastSyncAtEpochMs: Long? = null,
)

/** Complete state of the watch — reply to [WirePaths.STATUS_REQUEST]. */
@Serializable
data class WatchStatus(
    val configured: Boolean,
    val serverName: String? = null,
    val serverUrl: String? = null,
    val userName: String? = null,
    val usedBytes: Long = 0,
    val quotaBytes: Long = 0,
    val availableDiskBytes: Long = 0,
    val collections: List<WirePinnedCollection> = emptyList(),
    /** `SyncOutcome.name` of the last sync pass. */
    val lastSyncOutcome: String? = null,
    val lastSyncEpochMs: Long? = null,
    val appVersionName: String? = null,
    /**
     * `true` when [collections] was trimmed to keep the payload under the transport limit
     * (see [sizeCapped]). Backward compatible: an older peer ignores the field.
     */
    val collectionsTruncated: Boolean = false,
    /** `SyncState.name` when supplied by a newer watch. */
    val syncState: String? = null,
    /** Stable [WireSyncBlockers] value when user action is required. */
    val syncBlocker: String? = null,
)

/**
 * `MessageClient.sendMessage` rejects payloads above ~100 KB, and the sender-side failure
 * used to be swallowed — the phone only saw a timeout. Margin below the hard limit keeps
 * the cap safe against UTF-8 expansion of names and future field additions.
 */
const val MAX_STATUS_PAYLOAD_BYTES = 90_000

/**
 * Returns this status trimmed so its serialized form fits in [maxBytes].
 *
 * Trailing [WatchStatus.collections] entries are dropped first (the only unbounded part of
 * the payload); [WatchStatus.collectionsTruncated] is set whenever anything was dropped so
 * the phone can tell a short list from a cut one.
 */
fun WatchStatus.sizeCapped(maxBytes: Int = MAX_STATUS_PAYLOAD_BYTES): WatchStatus {
    if (toWireBytes().size <= maxBytes) return this
    var kept = collections
    while (kept.isNotEmpty()) {
        // Halving converges in log2(n) serializations instead of n.
        kept = kept.subList(0, kept.size / 2)
        val candidate = copy(collections = kept, collectionsTruncated = true)
        if (candidate.toWireBytes().size <= maxBytes) return candidate
    }
    return copy(collections = emptyList(), collectionsTruncated = true)
}

/** Pin/unpin command for a collection, sent by the phone. */
@Serializable
data class PinCommand(
    val collectionId: String,
    /** `CatalogType.name` on the phone side / `CollectionType.name` expected on the watch side. */
    val collectionType: String,
    val name: String,
    val trackCount: Int = 0,
    val pin: Boolean,
)

/**
 * Manual sync request. Empty legacy payloads decode with both consents `false` at the receiver.
 *
 * Both fields are explicit, per-request approvals. They are deliberately not persisted.
 */
@Serializable
data class SyncCommand(
    val allowMetered: Boolean = false,
    val allowLowBattery: Boolean = false,
)

/** Generic ACK — success or error with a [WireErrors] code. */
@Serializable
data class WireAck(
    val ok: Boolean,
    val error: String? = null,
)

// ── Encoders / decoders ─────────────────────────────────────────────────────────────

/** Serializes a DTO to UTF-8 bytes for the Data Layer payload. */
inline fun <reified T> T.toWireBytes(): ByteArray =
    WireJson.encodeToString(this).encodeToByteArray()

/**
 * Decodes a Data Layer payload into a DTO.
 *
 * @throws SerializationException if the payload is malformed or incompatible. Any
 *   Data Layer receiver must wrap this call in `runCatching` and ignore a malformed
 *   payload (log-free drop) — the sender times out and retries.
 */
inline fun <reified T> ByteArray.fromWire(): T =
    WireJson.decodeFromString(decodeToString())
