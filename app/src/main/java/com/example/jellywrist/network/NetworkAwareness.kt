package com.example.jellywrist.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.jellywrist.download.TrackDownloader
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.sync.SyncPreconditions
import com.example.jellywrist.util.SyncTrace
import com.google.android.horologist.networks.data.NetworkType
import com.google.android.horologist.networks.highbandwidth.HighBandwidthNetworkMediator
import com.google.android.horologist.networks.request.HighBandwidthRequest
import com.google.android.horologist.networks.status.NetworkRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Observation reseau et pilotage de la file de telechargement (PLAN.md §8 tache 12).
 *
 * S'appuie sur Horologist `network-awareness` (REQ-007) :
 * - [NetworkRepository] observe les reseaux reels de la montre (Wi-Fi / cell / proxy BT) ;
 * - [HighBandwidthNetworkMediator] **acquiert explicitement** le Wi-Fi pour la sync
 *   manuelle — indispensable : Wear OS n'allume pas le Wi-Fi spontanement et laisserait
 *   la sync ramper sur le proxy Bluetooth du telephone (PLAN.md §4.3).
 *
 * Les transitions sont traduites par [NetworkPolicy] (pur, teste sur JVM) puis appliquees
 * au port [TrackDownloader] : pause sans perte d'etat, reprise par Range (DEC-005).
 */
@Singleton
class NetworkAwareness @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val highBandwidthMediator: HighBandwidthNetworkMediator,
    /**
     * [dagger.Lazy]: this singleton is injected into the Application, and constructing
     * the concrete downloader pulls in Media3's `DownloadManager` + `SimpleCache` —
     * work that has no business inside `Application.onCreate` on the main thread. The
     * first network decision (always on [appScope]) constructs it instead.
     */
    private val downloader: dagger.Lazy<TrackDownloader>,
    private val settingsRepository: SettingsRepository,
    private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context,
) : SyncPreconditions {

    private val _isUnmetered = MutableStateFlow(false)

    /** `true` quand le reseau actif est non facture (Wi-Fi). Consomme par l'UI Downloads. */
    val isUnmetered: StateFlow<Boolean> = _isUnmetered.asStateFlow()

    /** Derniere action appliquee — l'UI l'affiche. */
    private val _lastAction = MutableStateFlow(NetworkPolicy.Action.CONTINUE)
    val lastAction: StateFlow<NetworkPolicy.Action> = _lastAction.asStateFlow()

    // -- Etats de batterie et reglages, mis a jour par les flux ci-dessous -----------

    private val _isCharging = MutableStateFlow(false)

    /**
     * `true` while a manual, user-authorised pass owns the queue.
     *
     * REQ-007 lifts the charging constraint for manual sync, but the charging observer did
     * not know that and paused the queue the manual pass had just resumed. Set when the
     * worker resumes downloads, cleared once the queue has drained.
     */
    private val _manualRunActive = MutableStateFlow(false)
    private val _requiresCharging = MutableStateFlow(true)
    private val _unmeteredOnly = MutableStateFlow(true)
    private val _batteryLevel = MutableStateFlow(100)

    /** Premiere emission reseau : un faux->faux ne doit pas laisser Media3 reprendre
     *  sur reseau facture (bug #4 du bot review). */
    private var firstNetworkEmission = true

    /** Demarre l'observation ; idempotent via [started]. Appele au demarrage de l'app. */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        // ── Observation reseau ──────────────────────────────────────────────

        networkRepository.networkStatus
            .map { networks ->
                val info = networks.activeNetwork?.networkInfo
                info?.type == NetworkType.Wifi && info.metered != true
            }
            .onEach { unmetered ->
                val was = _isUnmetered.value
                _isUnmetered.value = unmetered
                val raw = if (firstNetworkEmission) {
                    firstNetworkEmission = false
                    if (!unmetered) NetworkPolicy.Action.PAUSE_DOWNLOADS
                    else NetworkPolicy.Action.CONTINUE
                } else {
                    NetworkPolicy.decide(
                        wasUnmetered = was,
                        isUnmetered = unmetered,
                        syncRunning = true,
                        manualRequested = false,
                    )
                }
                // Respect du reglage "Wi-Fi uniquement" (bug #12, round 2)
                val action = if (!_unmeteredOnly.value &&
                    (raw == NetworkPolicy.Action.PAUSE_DOWNLOADS ||
                     raw == NetworkPolicy.Action.RESUME_DOWNLOADS)) {
                    NetworkPolicy.Action.CONTINUE
                } else { raw }
                apply(action)
            }
            .launchIn(appScope)

        combine(
            settingsRepository.settings.map { it.requiresCharging },
            settingsRepository.settings.map { it.unmeteredOnly },
        ) { requires, unmeteredOnly ->
            val wasOnly = _unmeteredOnly.value
            _requiresCharging.value = requires
            _unmeteredOnly.value = unmeteredOnly
            // Transition "Wi-Fi uniquement" desactivee alors que le reseau est facture :
            // reprendre les telechargements (bug #12 round 2 — le reglage changeait sans effet)
            if (wasOnly && !unmeteredOnly && !_isUnmetered.value) {
                apply(NetworkPolicy.Action.RESUME_DOWNLOADS)
            }
        }.launchIn(appScope)
        // ── Protection batterie + contrainte de charge (bug #5, round 1) ────

        combine(
            observeCharging().distinctUntilChanged(),
            settingsRepository.settings.map { it.requiresCharging },
        ) { charging, requires ->
            _isCharging.value = charging
            _requiresCharging.value = requires
            // `distinctUntilChanged` above is not cosmetic: ACTION_BATTERY_CHANGED is
            // broadcast constantly, and every single broadcast used to re-issue a pause.
            apply(
                NetworkPolicy.chargingAction(
                    charging = charging,
                    requiresCharging = requires,
                    manualRunActive = _manualRunActive.value,
                ),
            )
        }.launchIn(appScope)

        // A manual pass owns the queue until it has drained. Any snapshot with nothing left
        // to download means the run is over and the ordinary constraints apply again.
        downloader.get().observeProgress()
            .onEach { inFlight -> if (inFlight.isEmpty()) _manualRunActive.value = false }
            .launchIn(appScope)
    }

    // ── Observateurs ─────────────────────────────────────────────────────────

    private fun observeCharging(): Flow<Boolean> = callbackFlow {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                _batteryLevel.value = if (scale > 0) (level * 100) / scale else 100
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                trySend(
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                )
            }
        }
        context.registerReceiver(receiver, filter)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    // ── API publique ─────────────────────────────────────────────────────────

    /** Reveil explicite de la file — utilise par le worker quand l'utilisateur
     *  a consenti au reseau facture (REQ-007). Appele AVANT executeSync(). */
    suspend fun resumeDownloads() {
        // The user authorised this pass: the charging constraint must not undo it.
        _manualRunActive.value = true
        downloader.get().resumeAll()
    }

    /**
     * Ends the manual run's ownership of the queue — but only if it left nothing to download.
     *
     * The progress-based clearing above only fires when the queue *changes* to empty. A manual
     * pass that enqueued nothing — an already-synced library, no pins, a failure before the
     * enqueue — leaves the progress flow sitting on the same empty value it held before, so no
     * emission arrives and the override would outlive the run indefinitely, suppressing the
     * REQ-007 constraints for every later automatic pass. That is the case this closes.
     *
     * It must not close any other. `syncPass` returns once the tracks are *registered*, so a
     * pass that enqueued work is still downloading when the worker finishes; releasing the
     * override there would let the charging observer pause the very download the user
     * authorised off the charger. Those runs stay owned until the queue drains
     * ([NetworkPolicy.manualRunEndsWithPass]).
     */
    suspend fun manualRunFinished() {
        val outstanding = runCatching { downloader.get().hasOutstandingWork() }
            .onFailure { SyncTrace.w("could not count outstanding downloads", it) }
            .getOrDefault(true) // on doubt, keep the override: a stuck flag beats a stalled sync
        if (NetworkPolicy.manualRunEndsWithPass(outstanding)) {
            _manualRunActive.value = false
            SyncTrace.i("manual run released the queue: nothing was enqueued")
        } else {
            SyncTrace.i("manual run keeps the queue until it drains")
        }
    }

    /** REQ-007 : avertissement batterie avant sync manuelle (bug #10, round 2).
     *  Seuil a 20 % — en dessous, une sync de plusieurs Go risque d'epuiser la montre. */
    override fun shouldWarnBeforeManualSync(): Boolean =
        !_isCharging.value && _batteryLevel.value < 20

    override fun isUnmetered(): Boolean = _isUnmetered.value

    /**
     * Execute [block] sous **acquisition explicite du Wi-Fi** (sync manuelle, PLAN.md §4.3).
     */
    suspend fun <T> withHighBandwidth(block: suspend (granted: Boolean) -> T): T =
        highBandwidthMediator.requestHighBandwidthNetwork(HighBandwidthRequest.Wifi).use { lease ->
            val granted = lease.awaitGranted(GRANT_TIMEOUT_S.seconds)
            block(granted)
        }

    // ── Interne ──────────────────────────────────────────────────────────────

    private suspend fun apply(action: NetworkPolicy.Action) {
        _lastAction.value = action
        // Garde batterie : une reprise declenchee par le reseau (Wi-Fi->BT->Wi-Fi)
        // ne doit pas contourner la contrainte de charge (2e avis du bot review)
        val effective = when {
            action == NetworkPolicy.Action.RESUME_DOWNLOADS
                && _requiresCharging.value
                && !_isCharging.value
                && !_manualRunActive.value -> NetworkPolicy.Action.CONTINUE
            // Never resume onto a metered network while the user asked for Wi-Fi only.
            action == NetworkPolicy.Action.RESUME_DOWNLOADS
                && _unmeteredOnly.value
                && !_isUnmetered.value
                && !_manualRunActive.value -> NetworkPolicy.Action.CONTINUE
            // An authorised manual run outranks the automatic charging pause (REQ-007).
            action == NetworkPolicy.Action.PAUSE_DOWNLOADS
                && _manualRunActive.value -> NetworkPolicy.Action.CONTINUE
            else -> action
        }
        SyncTrace.i(
            "networkPolicy action=$action effective=$effective charging=${_isCharging.value} " +
                "requiresCharging=${_requiresCharging.value} unmetered=${_isUnmetered.value} " +
                "unmeteredOnly=${_unmeteredOnly.value} manualRun=${_manualRunActive.value}",
        )
        when (effective) {
            NetworkPolicy.Action.PAUSE_DOWNLOADS -> downloader.get().pauseAll()
            NetworkPolicy.Action.RESUME_DOWNLOADS -> downloader.get().resumeAll()
            NetworkPolicy.Action.CONTINUE, NetworkPolicy.Action.WARN_METERED -> Unit
        }
    }

    private companion object {
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        const val GRANT_TIMEOUT_S = 20
    }
}
