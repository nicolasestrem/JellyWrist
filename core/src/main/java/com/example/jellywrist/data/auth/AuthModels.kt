package com.example.jellywrist.data.auth

/**
 * Authenticated session persisted after a successful login.
 *
 * [accessToken] is a secret: never logged, never written to Room or to plain-text
 * preferences — only to [TokenStore] (EncryptedSharedPreferences / Keystore). See
 * PLAN.md §3.2 and decision DEC-002 (REQ-005).
 */
data class AuthSession(
    val baseUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val serverId: String?,
)

/** Result of reading the encrypted session store. */
sealed interface SessionLoadResult {
    val sessionOrNull: AuthSession?

    data class Restored(val session: AuthSession) : SessionLoadResult {
        override val sessionOrNull: AuthSession = session
    }

    data object Empty : SessionLoadResult {
        override val sessionOrNull: AuthSession? = null
    }

    /** The unreadable auth store was removed so the user can authenticate again. */
    data object RecoveredCorruptStore : SessionLoadResult {
        override val sessionOrNull: AuthSession? = null
    }
}

/** One-shot application bootstrap state derived from [SessionLoadResult]. */
sealed interface AuthBootstrapResult {
    val sessionOrNull: AuthSession?

    data class Authenticated(val session: AuthSession) : AuthBootstrapResult {
        override val sessionOrNull: AuthSession = session
    }

    data class SignedOut(val recoveredCorruptStore: Boolean = false) : AuthBootstrapResult {
        override val sessionOrNull: AuthSession? = null
    }
}

/**
 * Quick Connect request in progress.
 *
 * [code] is shown to the user (they enter it in their already-signed-in Jellyfin client);
 * [secret] is the opaque identifier used to poll the state and then exchange the approved
 * request for a token.
 */
data class QuickConnectHandle(val secret: String, val code: String)

/** Server-side state of a Quick Connect request. */
sealed interface QuickConnectState {
    /** Waiting for the user's approval. */
    data object Pending : QuickConnectState

    /** Approved: the secret can be exchanged for a token. */
    data object Approved : QuickConnectState

    /** Secret unknown, expired, or local wait deadline exceeded. */
    data object Expired : QuickConnectState

    /**
     * The server stopped responding during polling (network down, 5xx, TLS).
     *
     * A **terminal state distinct** from [Expired]: the Quick Connect request may still be
     * valid on the server side; it is the path to it that vanished. The caller therefore
     * offers a retry rather than a new code. Its existence is what allows
     * [com.example.jellywrist.data.repo.AuthRepository.pollQuickConnect] to never let an
     * exception escape — see the note on that flow.
     */
    data object Unavailable : QuickConnectState
}

/** Result of a Quick Connect authentication attempt (DEC-010: single path). */
sealed interface AuthResult {
    /** Authenticated: the session is already persisted in the [TokenStore]. */
    data class Success(val session: AuthSession) : AuthResult

    /** The Quick Connect request is no longer valid (expired or revoked). */
    data object QuickConnectExpired : AuthResult

    /** Network failure, unusable response, or server error. */
    data class Failed(val cause: Throwable) : AuthResult
}
