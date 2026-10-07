/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nv.pair.MainActivity
import com.nv.pair.data.PairRepository
import com.nv.pair.data.ClusterRepository
import com.nv.pair.data.ClusterInvite
import com.nv.pair.data.RouterRepository
import com.nv.pair.data.EngineProxyStatus
import com.nv.pair.data.UiPreferencesRepository
import com.nv.pair.mnn.MnnRuntimeContainer
import com.nv.pair.mnn.MnnErrorCode
import com.nv.pair.mnn.MnnSettingsRepository
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.network.MulticastLockManager
import com.nv.pair.network.AndroidNetworkContext
import com.nv.pair.rpc.BrokerSession
import com.nv.pair.rpc.BrokerApi
import com.nv.pair.rpc.parseNodesChanged
import com.nv.pair.rpc.ClusterApi
import com.nv.pair.rpc.parseClusterInvite
import com.nv.pair.rpc.RouterApi
import com.nv.pair.rpc.parseWorkloadRemoval
import com.nv.pair.rpc.parseWorkloadUpsert
import com.nv.pair.rpc.parseClusterMembersChanged
import com.nv.pair.rpc.parseClusterMembers
import com.nv.pair.runtime.RuntimePhase.CRASHED
import com.nv.pair.runtime.RuntimePhase.RESTART_BACKOFF
import com.nv.pair.runtime.RuntimePhase.RUNNING
import com.nv.pair.runtime.RuntimePhase.STARTING
import com.nv.pair.runtime.RuntimePhase.STARTUP_FAILED
import com.nv.pair.runtime.RuntimePhase.STOPPED
import com.nv.pair.runtime.RuntimePhase.STOPPING
import com.nv.pair.runtime.RuntimePhase.WAITING_READY
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PairRuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private lateinit var preferences: UiPreferencesRepository
    private lateinit var mnnSettings: MnnSettingsRepository
    private lateinit var multicastLock: MulticastLockManager
    private var runtimeJob: Job? = null
    private var mnnRuntimeContainer: MnnRuntimeContainer? = null
    private val activeSession = AtomicReference<BrokerSession?>()
    @Volatile
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        preferences = UiPreferencesRepository(applicationContext)
        mnnSettings = MnnSettingsRepository(applicationContext)
        serviceScope.launch {
            mnnSettings.preferredBackend.collect { backend ->
                applyPreferredMnnBackend(backend)
            }
        }
        multicastLock = MulticastLockManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch { commandMutex.withLock { stopRuntime(startId) } }
            ACTION_MNN_BACKEND_CHANGED -> serviceScope.launch {
                applyPreferredMnnBackend(MnnBackend.fromPreferenceValue(intent.getStringExtra(EXTRA_MNN_BACKEND)))
            }
            ACTION_CLUSTER_CREATE, ACTION_CLUSTER_INVITE, ACTION_CLUSTER_RESPOND,
            ACTION_CLUSTER_CANCEL, ACTION_CLUSTER_LEAVE, ACTION_CLUSTER_REMOVE ->
                serviceScope.launch { performClusterAction(intent.action.orEmpty(), intent) }
            ACTION_START -> {
                promoteToForeground()
                serviceScope.launch {
                    commandMutex.withLock {
                        preferences.setDesiredRuntimeRunning(true)
                        setDesiredRunning(true)
                        startRuntimeIfNeeded()
                    }
                }
            }
            null -> {
                promoteToForeground()
                serviceScope.launch {
                    commandMutex.withLock {
                        if (preferences.readDesiredRuntimeRunning()) {
                            setDesiredRunning(true)
                            startRuntimeIfNeeded()
                        } else {
                            finishStoppedService(startId)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        multicastLock.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRuntimeIfNeeded() {
        if (runtimeJob?.isActive == true) return
        runtimeJob = serviceScope.launch { runBrokerLoop() }
    }

    private suspend fun runBrokerLoop() {
        val restartPolicy = BrokerRestartPolicy()
        try {
            multicastLock.acquire()
            val mnnAvailable = startOptionalMnnRuntime()
            while (currentCoroutineContext().isActive && runtimeState.value.desiredRunning) {
                setPhase(STARTING)
                val crash = CompletableDeferred<Int>()
                val session = BrokerSession(
                    binaries = NativeBinaryRegistry(File(applicationInfo.nativeLibraryDir)),
                    filesDir = filesDir,
                    cacheDir = cacheDir,
                    onLog = { line -> android.util.Log.i(TAG, line) },
                    onCrash = { code -> crash.complete(code) },
                    onWaitingReady = { setPhaseIf(STARTING, WAITING_READY) },
                    additionalEnvironment = mdnsEnvironment(),
                    proxyEngines = BrokerSession.proxyEnginesForLocalMnn(mnnAvailable),
                    onNotification = { notification ->
                        if (notification.method.startsWith("cluster:invite-")) {
                            android.util.Log.i(TAG, "cluster invite notification received method=${notification.method}")
                        }
                        if (notification.method == "discovery:nodes-changed") {
                            val applied = runCatching {
                                pairRepository.applyNodesChanged(parseNodesChanged(notification.paramsJson))
                            }.onFailure { android.util.Log.w(TAG, "invalid discovery snapshot") }
                            if (applied.isSuccess) refreshNotification()
                        } else if (notification.method == "cluster:identity-changed") {
                            runCatching {
                                val params = notification.paramsJson?.let { org.json.JSONObject(it) }
                                    ?: org.json.JSONObject()
                                clusterRepository.applyClusterIdentityChanged(
                                    params.optString("clusterId"), params.optString("clusterFriendlyName"),
                                )
                            }.onFailure { android.util.Log.w(TAG, "invalid cluster identity notification") }
                        } else if (notification.method == "nodes:changed") {
                            runCatching { clusterRepository.applyMembersChanged(parseClusterMembersChanged(notification.paramsJson)) }
                        } else if (notification.method.startsWith("cluster:invite-")) {
                            val inbound = notification.method == "cluster:invite-received"
                            runCatching {
                                val payload = notification.paramsJson?.let { org.json.JSONObject(it) }
                                    ?: throw IllegalArgumentException("missing invite payload")
                                val invite = parseClusterInvite(payload, inbound)
                                if (invite.state == "canceled" || invite.state == "expired" ||
                                    invite.state == "declined" || invite.state == "failed" || invite.state == "paired") {
                                    clusterRepository.applyInvite(invite.copy(pin = null))
                                } else {
                                    clusterRepository.applyInvite(invite)
                                }
                            }.onFailure { android.util.Log.w(TAG, "invalid cluster invite notification") }
                        } else if (notification.method == "workloads:upsert") {
                            runCatching { routerRepository.upsertWorkload(parseWorkloadUpsert(notification.paramsJson)) }
                                .onFailure { android.util.Log.w(TAG, "invalid workload update") }
                        } else if (notification.method == "workloads:remove") {
                            runCatching {
                                val removed = parseWorkloadRemoval(notification.paramsJson)
                                routerRepository.removeWorkload(removed.workloadId, removed.origin)
                            }.onFailure { android.util.Log.w(TAG, "invalid workload removal") }
                        }
                    },
                )
                activeSession.set(session)
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    val info = withContext(Dispatchers.IO) { session.start() }
                    if (!currentCoroutineContext().isActive || !runtimeState.value.desiredRunning) {
                        throw CancellationException("PAIR runtime is stopping")
                    }
                    withContext(Dispatchers.IO) { session.initializeDiscovery(pairRepository) }
                    clusterRepository.clearPendingInvites()
                    runCatching {
                        withContext(Dispatchers.IO) { ClusterApi(session).initialize(clusterRepository) }
                    }.onSuccess {
                        clusterRepository.setError(null)
                    }.onFailure {
                        clusterRepository.setError("Cluster manager is unavailable; cluster features are disabled.")
                    }
                    withContext(Dispatchers.IO) { RouterApi(session).initializeWorkloads(routerRepository) }
                    setPhase(RUNNING, version = info.version, uptimeMillis = info.uptimeMillis)
                    val proxyMonitor = serviceScope.launch { monitorProxyStatus(session) }
                    try {
                        val exitCode = crash.await()
                        val runtimeMillis = SystemClock.elapsedRealtime() - startedAt
                        restartPolicy.recordRuntime(runtimeMillis)
                        setPhase(CRASHED, error = "PAIR broker exited with code $exitCode")
                    } finally {
                        proxyMonitor.cancelAndJoin()
                        routerRepository.resetProxyStatuses()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    setPhase(STARTUP_FAILED, error = failure.message ?: "PAIR broker failed to start")
                } finally {
                    routerRepository.resetProxyStatuses()
                    activeSession.compareAndSet(session, null)
                    withContext(Dispatchers.IO) { session.close() }
                    clusterRepository.clearPendingInvites()
                }

                if (!runtimeState.value.desiredRunning) break
                val nextDelay = restartPolicy.nextDelayMillis()
                if (nextDelay == null) {
                    if (runtimeState.value.phase != STARTUP_FAILED) {
                        setPhase(STARTUP_FAILED, error = runtimeState.value.error ?: "PAIR broker restart limit reached")
                    }
                    preferences.setDesiredRuntimeRunning(false)
                    setDesiredRunning(false)
                    break
                }
                setPhase(RESTART_BACKOFF, error = "PAIR will retry in ${nextDelay / 1_000} seconds")
                delay(nextDelay)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (runtimeState.value.phase != STARTUP_FAILED) {
                setPhase(STARTUP_FAILED, error = failure.message ?: "PAIR runtime failed")
            }
            preferences.setDesiredRuntimeRunning(false)
            setDesiredRunning(false)
        } finally {
            routerRepository.resetProxyStatuses()
            val container = mnnRuntimeContainer
            mnnRuntimeContainer = null
            if (container != null) {
                withContext(NonCancellable + Dispatchers.IO) { container.close() }
            }
            _mnnLocalEngine.value = MnnLocalEngineStatus(available = false)
            multicastLock.release()
        }
    }

    private suspend fun startOptionalMnnRuntime(): Boolean {
        _mnnLocalEngine.value = MnnLocalEngineStatus(available = false)
        var candidate: MnnRuntimeContainer? = null
        try {
            val container = withContext(Dispatchers.IO) {
                MnnRuntimeContainer(
                    File(filesDir, MNN_MODELS_DIRECTORY),
                    preferredBackend = mnnSettings.readPreferredBackend(),
                )
            }
            candidate = container
            withContext(Dispatchers.IO) { container.start() }
            val health = withContext(Dispatchers.IO) { container.health() }
            if (!health.available) {
                closeUnstartedMnnContainer(container)
                val errorCode = health.error?.code ?: MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE
                _mnnLocalEngine.value = MnnLocalEngineStatus(
                    available = false,
                    backend = _preferredMnnBackend.value,
                    errorCode = errorCode,
                )
                android.util.Log.w(TAG, "local MNN unavailable code=${errorCode.name.lowercase()}")
                return false
            }
            mnnRuntimeContainer = container
            val preferredBackend = withContext(Dispatchers.IO) { mnnSettings.readPreferredBackend() }
            container.setPreferredBackend(preferredBackend)
            _preferredMnnBackend.value = preferredBackend
            _mnnLocalEngine.value = MnnLocalEngineStatus(available = true, backend = preferredBackend)
            return true
        } catch (cancelled: CancellationException) {
            candidate?.let { closeUnstartedMnnContainer(it) }
            throw cancelled
        } catch (_: Exception) {
            candidate?.let { closeUnstartedMnnContainer(it) }
            val errorCode = MnnErrorCode.INTERNAL_ERROR
            _mnnLocalEngine.value = MnnLocalEngineStatus(
                available = false,
                backend = _preferredMnnBackend.value,
                errorCode = errorCode,
            )
            android.util.Log.w(TAG, "local MNN startup failed code=${errorCode.name.lowercase()}")
            return false
        }
    }

    private fun applyPreferredMnnBackend(backend: MnnBackend) {
        _preferredMnnBackend.value = backend
        mnnRuntimeContainer?.setPreferredBackend(backend)
        if (_mnnLocalEngine.value.available) {
            _mnnLocalEngine.value = _mnnLocalEngine.value.copy(backend = backend, errorCode = null)
        }
    }

    private suspend fun closeUnstartedMnnContainer(container: MnnRuntimeContainer) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { container.close() }
        }
    }

    private suspend fun stopRuntime(startId: Int) {
        preferences.setDesiredRuntimeRunning(false)
        setDesiredRunning(false)
        if (runtimeState.value.phase != STOPPED) setPhase(STOPPING)
        val session = activeSession.get()
        if (session != null) {
            val leaveFailed = withContext(Dispatchers.IO) {
                runCatching { ClusterApi(session).leave() }.isFailure
            }
            if (leaveFailed) {
                clusterRepository.setError("Could not notify cluster peers before PAIR stopped.")
                android.util.Log.w(TAG, "cluster leave failed during runtime shutdown")
            }
        }
        closeMnnRuntime()
        if (session != null) withContext(Dispatchers.IO) { session.close() }
        runtimeJob?.cancelAndJoin()
        runtimeJob = null
        activeSession.set(null)
        multicastLock.release()
        if (runtimeState.value.phase != STOPPED) setPhase(STOPPED)
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelfResult(startId)
    }

    private suspend fun closeMnnRuntime() {
        val container = mnnRuntimeContainer
        mnnRuntimeContainer = null
        if (container != null) {
            withContext(NonCancellable + Dispatchers.IO) { container.close() }
        }
    }

    private fun finishStoppedService(startId: Int) {
        if (runtimeState.value.phase != STOPPED) setPhase(STOPPED, desiredRunning = false)
        multicastLock.release()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelfResult(startId)
    }

    private fun setDesiredRunning(desired: Boolean) {
        synchronized(stateLock) {
            _runtimeState.value = _runtimeState.value.copy(desiredRunning = desired)
        }
        refreshNotification()
    }

    private fun mdnsEnvironment(): Map<String, String> {
        val wifi = AndroidNetworkContext(applicationContext).wifiInterface() ?: return emptyMap()
        return mapOf(
            "NVPAIR_MDNS_INTERFACE_INDEX" to wifi.index.toString(),
            "NVPAIR_MDNS_INTERFACE_NAME" to wifi.name,
            "NVPAIR_MDNS_IPV4" to wifi.ipv4Address,
        )
    }

    private suspend fun monitorProxyStatus(session: BrokerSession) {
        while (currentCoroutineContext().isActive && activeSession.get() === session && runtimeState.value.phase == RUNNING) {
            for (engine in RouterRepository.ROUTER_ENGINES) {
                val status = runCatching {
                    withContext(Dispatchers.IO) { RouterApi(session).proxyStatus(engine) }
                }.getOrNull() ?: EngineProxyStatus(engine, false, 0)
                routerRepository.setProxyStatus(status)
            }
            mnnRuntimeContainer?.let { container ->
                val (health, status) = withContext(Dispatchers.IO) {
                    container.health() to container.runtimeStatus()
                }
                _mnnLocalEngine.value = MnnLocalEngineStatus(
                    available = health.available,
                    backend = container.preferredBackend(),
                    errorCode = status.error?.code,
                )
            }
            delay(2_000)
        }
    }

    private fun performClusterAction(action: String, intent: Intent) {
        val session = activeSession.get() ?: run {
            clusterRepository.setError("Start PAIR before managing cluster membership.")
            return
        }
        val api = ClusterApi(session)
        val text = intent.getStringExtra(EXTRA_VALUE).orEmpty()
        clusterRepository.setBusy(true)
        clusterRepository.setError(null)
        serviceScope.launch {
            var inviteToWatch: String? = null
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        ACTION_CLUSTER_CREATE -> api.create(text)
                        ACTION_CLUSTER_INVITE -> {
                            val node = pairRepository.nodes.value.firstOrNull { it.id == text }
                                ?: throw IllegalArgumentException("Discovered node is no longer available.")
                            val invite = api.invite(node.ipAddress, CLUSTER_MANAGER_PORT, node.id)
                            clusterRepository.applyInvite(invite)
                            if (invite.state == "pending") inviteToWatch = invite.inviteId
                        }
                        ACTION_CLUSTER_RESPOND -> {
                            val inviteId = intent.getStringExtra(EXTRA_VALUE).orEmpty()
                            val pin = intent.getStringExtra(EXTRA_SECONDARY).orEmpty()
                            val accepted = intent.getBooleanExtra(EXTRA_ACCEPT, false)
                            clusterRepository.applyInvite(api.respond(inviteId, accepted, pin).copy(pin = null))
                        }
                        ACTION_CLUSTER_CANCEL -> {
                            api.cancel(text)
                            clusterRepository.removeInvite(text)
                        }
                        ACTION_CLUSTER_LEAVE -> api.leave()
                        ACTION_CLUSTER_REMOVE -> api.remove(text)
                    }
                    if (action == ACTION_CLUSTER_LEAVE || action == ACTION_CLUSTER_REMOVE) {
                        clusterRepository.applyMembersChanged(parseClusterMembers(session.request("nodes:get-initial")))
                    }
                }
            } catch (failure: Exception) {
                clusterRepository.setError(failure.message ?: "Cluster operation failed.")
            } finally {
                clusterRepository.setBusy(false)
            }
            inviteToWatch?.let { inviteId ->
                serviceScope.launch {
                    while (isActive && runtimeState.value.phase == RUNNING) {
                        delay(2_000)
                        val statusResult = runCatching {
                            withContext(Dispatchers.IO) { api.inviteStatus(inviteId) }
                        }
                        if (statusResult.isFailure) {
                            clusterRepository.applyInvite(ClusterInvite(
                                inviteId = inviteId,
                                fromNodeId = "",
                                state = "failed",
                            ))
                            break
                        }
                        val updated = statusResult.getOrThrow()
                        clusterRepository.applyInvite(updated.copy(pin = if (updated.state == "pending") updated.pin else null))
                        if (updated.state != "pending") break
                    }
                }
            }
        }
    }

    private fun setPhaseIf(expected: RuntimePhase, next: RuntimePhase) {
        val changed = synchronized(stateLock) {
            if (_runtimeState.value.phase != expected) {
                false
            } else {
                _runtimeState.value = _runtimeState.value.transitionTo(next)
                true
            }
        }
        if (changed) refreshNotification()
    }

    private fun setPhase(
        phase: RuntimePhase,
        desiredRunning: Boolean = runtimeState.value.desiredRunning,
        version: String? = runtimeState.value.version,
        uptimeMillis: Long? = runtimeState.value.uptimeMillis,
        error: String? = null,
    ) {
        synchronized(stateLock) {
            _runtimeState.value = _runtimeState.value.transitionTo(
                next = phase,
                desiredRunning = desiredRunning,
                version = version,
                uptimeMillis = uptimeMillis,
                error = error,
            )
        }
        refreshNotification()
    }

    private fun promoteToForeground() {
        if (foreground) return
        startForeground(NOTIFICATION_ID, buildNotification())
        foreground = true
    }

    private fun refreshNotification() {
        if (foreground) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, PairRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = runtimeState.value
        val content = when (state.phase) {
            RUNNING -> "Nodes: ${pairRepository.nodes.value.size} | Status: Connected"
            STARTING, WAITING_READY, RESTART_BACKOFF -> "Status: Starting"
            STOPPING -> "Status: Stopping"
            STARTUP_FAILED, CRASHED -> "Status: Needs attention"
            STOPPED -> "Status: Stopped"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (state.phase == RUNNING) "PAIR is running" else "PAIR runtime")
            .setContentText(content)
            .setContentIntent(open)
            .setOngoing(state.desiredRunning)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PAIR runtime",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.nv.pair.action.START_RUNTIME"
        const val ACTION_STOP = "com.nv.pair.action.STOP_RUNTIME"
        const val ACTION_MNN_BACKEND_CHANGED = "com.nv.pair.action.MNN_BACKEND_CHANGED"
        const val ACTION_CLUSTER_CREATE = "com.nv.pair.action.CLUSTER_CREATE"
        const val ACTION_CLUSTER_INVITE = "com.nv.pair.action.CLUSTER_INVITE"
        const val ACTION_CLUSTER_RESPOND = "com.nv.pair.action.CLUSTER_RESPOND"
        const val ACTION_CLUSTER_CANCEL = "com.nv.pair.action.CLUSTER_CANCEL"
        const val ACTION_CLUSTER_LEAVE = "com.nv.pair.action.CLUSTER_LEAVE"
        const val ACTION_CLUSTER_REMOVE = "com.nv.pair.action.CLUSTER_REMOVE"
        const val EXTRA_VALUE = "com.nv.pair.extra.VALUE"
        const val EXTRA_SECONDARY = "com.nv.pair.extra.SECONDARY"
        const val EXTRA_ACCEPT = "com.nv.pair.extra.ACCEPT"
        const val EXTRA_MNN_BACKEND = "com.nv.pair.extra.MNN_BACKEND"

        private const val TAG = "PAIR-Runtime"
        private const val CHANNEL_ID = "pair-runtime"
        private const val NOTIFICATION_ID = 36
        private const val OPEN_REQUEST_CODE = 1
        private const val STOP_REQUEST_CODE = 2
        private const val CLUSTER_MANAGER_PORT = 14321
        private const val MNN_MODELS_DIRECTORY = "mnn/models"
        private val stateLock = Any()
        private val _runtimeState = MutableStateFlow(PairRuntimeState.stopped())
        private val pairRepository = PairRepository()
        private val clusterRepository = ClusterRepository()
        private val routerRepository = RouterRepository()

        val runtimeState: StateFlow<PairRuntimeState> = _runtimeState.asStateFlow()
        val discoveredNodes = pairRepository.nodes
        val clusterState = clusterRepository.state
        val proxyStatuses = routerRepository.proxies
        private val _mnnLocalEngine = MutableStateFlow(MnnLocalEngineStatus(available = false))
        val mnnLocalEngine: StateFlow<MnnLocalEngineStatus> = _mnnLocalEngine.asStateFlow()
        private val _preferredMnnBackend = MutableStateFlow(MnnBackend.CPU)
        val workloads = routerRepository.workloads
    }
}
