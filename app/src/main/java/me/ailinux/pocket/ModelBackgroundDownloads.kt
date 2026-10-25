package me.ailinux.pocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Private resumable transfer journal; never includes OAuth credentials. */
internal object ModelDownloadJournal {
    data class Entry(
        val id: Int, val name: String, val url: String, val hash: String?,
        val wifiOnly: Boolean, val state: String, val done: Long = 0,
        val total: Long = -1, val error: String = "", val attempts: Int = 0,
        val updatedAtMs: Long = 0, val lastStopReason: Int = -1
    )
    private const val PREFS = "pocket_background_downloads"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(name: String) = "model_" + ModelStore.sanitize(name)
    private fun parse(raw: String?): Entry? = runCatching {
        val j = JSONObject(raw ?: return@runCatching null)
        Entry(j.getInt("id"), j.getString("name"), j.getString("url"),
            j.optString("hash").ifBlank { null }, j.optBoolean("wifiOnly", false),
            j.optString("state"), j.optLong("done"), j.optLong("total", -1),
            j.optString("error"), j.optInt("attempts"),
            j.optLong("updatedAtMs", 0L), j.optInt("lastStopReason", -1))
    }.getOrNull()

    @Synchronized fun read(context: Context, name: String): Entry? =
        parse(prefs(context).getString(key(name), null))

    @Synchronized fun all(context: Context): List<Entry> {
        val p = prefs(context)
        return p.getStringSet("names", emptySet()).orEmpty()
            .mapNotNull { parse(p.getString(key(it), null)) }
    }

    @Synchronized fun write(context: Context, entry: Entry) {
        val p = prefs(context)
        val j = JSONObject().put("id", entry.id).put("name", entry.name)
            .put("url", entry.url).put("hash", entry.hash ?: "")
            .put("wifiOnly", entry.wifiOnly).put("state", entry.state)
            .put("done", entry.done).put("total", entry.total)
            .put("error", entry.error.take(220)).put("attempts", entry.attempts)
            .put("updatedAtMs", System.currentTimeMillis())
            .put("lastStopReason", entry.lastStopReason)
        val names = p.getStringSet("names", emptySet()).orEmpty().toMutableSet()
        names.add(entry.name)
        check(p.edit().putString(key(entry.name), j.toString())
            .putStringSet("names", names).commit()) {
            "Downloadjournal konnte nicht gespeichert werden"
        }
    }

    @Synchronized fun change(context: Context, name: String, update: (Entry) -> Entry) {
        val current = read(context, name) ?: return
        write(context, update(current))
    }
}

/**
 * API 34+ runs Android user-initiated transfer jobs; API 33 runs WorkManager
 * foreground workers. Each job is begun by explicit UI confirmation only.
 */
internal object BackgroundModelDownloads {
    private const val ID_OFFSET = 26000
    fun jobId(name: String): Int = ID_OFFSET + (ModelStore.sanitize(name).hashCode() and 0x3fffffff)

    /**
     * Read the OS reason, not the journal's stale "queued" label. API 36
     * exposes all reasons, API 34+ the first. This call is read-only and
     * must never reschedule work from a polling Activity.
     */
    fun networkInfo(context: Context): DownloadSchedulerState.NetworkInfo? =
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                ?: return@runCatching null
            DownloadSchedulerState.NetworkInfo(
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            )
        }.getOrNull()

    fun snapshot(
        context: Context,
        entry: ModelDownloadJournal.Entry
    ): DownloadSchedulerState.View {
        var present: Boolean? = null
        val reasons = mutableSetOf<DownloadSchedulerState.Reason>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            present = runCatching { scheduler.getPendingJob(entry.id) != null }.getOrNull()
            if (present == true && entry.state == "queued") {
                val pending = runCatching {
                    if (Build.VERSION.SDK_INT >= 36)
                        scheduler.getPendingJobReasons(entry.id).toList()
                    else listOf(scheduler.getPendingJobReason(entry.id))
                }.getOrElse { emptyList() }
                for (reason in pending) {
                    when (reason) {
                        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY ->
                            reasons.add(DownloadSchedulerState.Reason.NETWORK)
                        JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION,
                        JobScheduler.PENDING_JOB_REASON_APP_STANDBY,
                        JobScheduler.PENDING_JOB_REASON_QUOTA ->
                            reasons.add(DownloadSchedulerState.Reason.BACKGROUND_RESTRICTION)
                        JobScheduler.PENDING_JOB_REASON_DEVICE_STATE,
                        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW,
                        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING,
                        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE ->
                            reasons.add(DownloadSchedulerState.Reason.DEVICE_STATE)
                        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW ->
                            reasons.add(DownloadSchedulerState.Reason.STORAGE)
                        JobScheduler.PENDING_JOB_REASON_EXECUTING ->
                            reasons.add(DownloadSchedulerState.Reason.EXECUTING)
                        JobScheduler.PENDING_JOB_REASON_APP,
                        JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION ->
                            reasons.add(DownloadSchedulerState.Reason.OPTIMIZATION)
                        JobScheduler.PENDING_JOB_REASON_USER ->
                            reasons.add(DownloadSchedulerState.Reason.USER)
                    }
                }
            }
            // If the scheduler reports no reason, give a grounded basic
            // network hint. Never label unknown Android policy as "offline".
            if (present == true && entry.state == "queued" && reasons.isEmpty()) {
                val network = runCatching {
                    val manager = context.getSystemService(ConnectivityManager::class.java)
                    val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                    val internet = caps?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    val wifi = manager.allNetworks.any { id ->
                        manager.getNetworkCapabilities(id)?.let {
                            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        } == true
                    }
                    !internet || (entry.wifiOnly && !wifi)
                }.getOrDefault(false)
                if (network) reasons.add(DownloadSchedulerState.Reason.NETWORK)
            }
        }
        val age = (System.currentTimeMillis() - entry.updatedAtMs)
            .coerceAtLeast(0L)
        return DownloadSchedulerState.describe(
            entry.name, entry.state, entry.wifiOnly, present,
            reasons, age, entry.done, entry.total, entry.error,
            network = networkInfo(context)
        )
    }

    /**
     * Explicit UI action only. Reschedule only a queued, not-yet-running
     * UIDT job; never silently reschedule after a force-stop or system Stop.
     * The on-disk .part and .part.validator remain untouched.
     */
    fun resume(context: Context, name: String, requeueWaiting: Boolean = false) {
        val entry = ModelDownloadJournal.read(context, name)
            ?: error("Keine gespeicherten Downloadinformationen vorhanden.")
        require(entry.url.startsWith("https://")) {
            "Die Downloadquelle wurde nicht gespeichert; bitte erneut auswählen."
        }
        val noLongerScheduled = if (Build.VERSION.SDK_INT >= 34)
            context.getSystemService(JobScheduler::class.java).getPendingJob(entry.id) == null
        else false
        require(entry.state in setOf("queued", "paused", "failed") ||
            (entry.state in setOf("running", "verifying") && noLongerScheduled)) {
            "Der Download ist aktiv, wird geprüft oder ist bereits installiert."
        }
        if (entry.state == "queued" && Build.VERSION.SDK_INT >= 34) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(entry.id) != null) {
                require(requeueWaiting) {
                    "Android verwaltet diesen Job noch; erst Wartegrund prüfen."
                }
                // This is only called after a new explicit button press and
                // only when the journal says QUEUED (not RUNNING).
                ModelDownloadJournal.change(context, name) {
                    it.copy(state = "paused")
                }
                scheduler.cancel(entry.id)
            }
        }
        start(context, RemoteModel(entry.name, entry.url,
            if (entry.total > 0) entry.total else -1L, entry.hash),
            entry.wifiOnly)
    }

    fun start(context: Context, model: RemoteModel, wifiOnly: Boolean) {
        val name = ModelStore.sanitize(model.name)
        require(ModelStore.isModelName(name) && model.url.startsWith("https://")) {
            "Nur Modell-Downloads über HTTPS erlaubt"
        }
        require(!java.io.File(ModelStore(context).dir, name).exists()) {
            "Modell ist bereits installiert"
        }
        val id = jobId(name)
        require(ModelDownloadJournal.all(context).none { it.id == id && it.name != name }) {
            "Download-ID-Kollision; verwende einen anderen Dateinamen"
        }
        val previous = ModelDownloadJournal.read(context, name)
        // After a force-stop or process loss the OS may have dropped a job
        // even though its durable journal still says queued/running.
        val scheduled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            context.getSystemService(JobScheduler::class.java).getPendingJob(id) != null
        else previous?.let {
            runCatching {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork("pocket-model-" + name).get()
                    .any { work -> !work.state.isFinished }
            }.getOrDefault(true)
        } ?: false
        require(previous == null || previous.state !in setOf("queued", "running") ||
            !scheduled) { "Dieser Modelldownload läuft bereits" }
        if (java.io.File(ModelStore(context).dir, name + ".part").exists()) {
            require(previous == null || previous.url == model.url) {
                "Teil-Download gehört zu einer anderen URL"
            }
        }
        val initialBytes = java.io.File(ModelStore(context).dir, name + ".part")
            .takeIf { it.isFile }?.length() ?: 0L
        val entry = ModelDownloadJournal.Entry(id, name, model.url, model.sha256,
            wifiOnly, "queued", done = initialBytes,
            total = if (model.bytes > 0) model.bytes else previous?.total ?: -1L)
        ModelDownloadJournal.write(context, entry)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // NetworkRequest.Builder includes NOT_VPN by default. A
                // UIDT should also work with a validated VPN/private-DNS
                // network; otherwise Android may keep it queued forever,
                // while ordinary MCP HTTPS requests succeed.
                // Wi-Fi-only remains a separate transport constraint.
                val network = NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .apply {
                        if (wifiOnly) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    }.build()
                val extras = PersistableBundle().apply { putString("name", name) }
                // A resumed 2-3 GB download may have all bytes in its .part
                // already. Report remaining traffic to JobScheduler, not
                // the full historical size. Still budget HTTPS 416/HEAD
                // validators and keep the normal network requirement.
                val networkHint = ModelTransferNetworkEstimate.forResume(
                    entry.total, initialBytes)
                val info = JobInfo.Builder(id,
                    ComponentName(context, ModelTransferJobService::class.java))
                    .setExtras(extras)
                    .setUserInitiated(true)
                    .setRequiredNetwork(network)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .apply {
                        setMinimumNetworkChunkBytes(networkHint.minChunkBytes)
                        if (networkHint.known)
                            setEstimatedNetworkBytes(networkHint.expectedDownloadBytes, 0L)
                    }
                    .build()
                val scheduler = context.getSystemService(JobScheduler::class.java)
                check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) {
                    "Android hat den Downloadjob nicht angenommen"
                }
            } else {
                val constraints = Constraints.Builder().setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                ).build()
                val work = OneTimeWorkRequestBuilder<ModelTransferWorker>()
                    .setConstraints(constraints)
                    .setInputData(Data.Builder().putString("name", name).build())
                    .addTag("pocket-model-download")
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "pocket-model-" + name, ExistingWorkPolicy.REPLACE, work)
            }
        } catch (error: Exception) {
            ModelDownloadJournal.change(context, name) {
                it.copy(state = "failed", error = error.message.orEmpty())
            }
            throw error
        }
    }

    fun pause(context: Context, name: String) {
        val entry = ModelDownloadJournal.read(context, name) ?: return
        ModelDownloadJournal.change(context, name) { it.copy(state = "paused") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            context.getSystemService(JobScheduler::class.java).cancel(entry.id)
        else WorkManager.getInstance(context).cancelUniqueWork("pocket-model-" + entry.name)
    }

    fun latestActive(context: Context): ModelDownloadJournal.Entry? {
        val entries = ModelDownloadJournal.all(context)
            .filter { it.url.isNotBlank() }
        return entries.filter {
            it.state in setOf("running", "queued", "verifying")
        }.maxByOrNull { it.updatedAtMs } ?:
            entries.filter {
                it.state in setOf("paused", "failed")
            }.maxByOrNull { it.updatedAtMs }
    }
}

internal object TransferNotification {
    private const val CHANNEL = "pocket_model_downloads"
    fun build(context: Context, entry: ModelDownloadJournal.Entry, ongoing: Boolean = true): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Pocket AI model downloads",
            NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(context, ModelTransferPauseReceiver::class.java).apply {
            putExtra("name", entry.name)
            action = "me.ailinux.pocket.PAUSE_MODEL"
        }
        val pause = PendingIntent.getBroadcast(context, entry.id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val launch = PendingIntent.getActivity(context, entry.id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val progress = if (entry.total > 0)
            ((entry.done * 100 / entry.total).coerceIn(0, 100)).toInt() else 0
        val stateText = when(entry.state) {
            "complete" -> "Downloaded and verified"
            "failed" -> "Failed: " + entry.error.take(65)
            "verifying" -> "Verifying model file..."
            "paused" -> "Paused. Open Pocket AI to resume."
            else -> ModelStore.displaySize(entry.done) + " downloaded"
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Pocket AI: " + entry.name.take(48))
            .setContentText(stateText)
            .setContentIntent(launch)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, progress, entry.total <= 0)
            .apply {
                if (ongoing) addAction(android.R.drawable.ic_media_pause, "Pause", pause)
            }
            .build()
    }
    fun update(context: Context, entry: ModelDownloadJournal.Entry, ongoing: Boolean = true) {
        context.getSystemService(NotificationManager::class.java)
            .notify(entry.id, build(context, entry, ongoing))
    }
}

class ModelTransferPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "me.ailinux.pocket.PAUSE_MODEL") return
        val name = intent.getStringExtra("name") ?: return
        BackgroundModelDownloads.pause(context, name)
        ModelDownloadJournal.read(context, name)?.let {
            TransferNotification.update(context, it, false)
        }
    }
}

class ModelTransferJobService : JobService() {
    private val executor = Executors.newFixedThreadPool(2)
    private val cancelled = ConcurrentHashMap<Int, AtomicBoolean>()
    private val stopped = ConcurrentHashMap.newKeySet<Int>()

    override fun onStartJob(params: JobParameters): Boolean {
        val name = params.extras.getString("name") ?: return false
        val existing = ModelDownloadJournal.read(this, name) ?: return false
        if (existing.state in setOf("paused", "complete")) return false
        val flag = AtomicBoolean(false)
        cancelled[params.jobId] = flag
        stopped.remove(params.jobId)
        val ready = existing.copy(state = "running", error = "", lastStopReason = -1)
        ModelDownloadJournal.write(this, ready)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                setNotification(params, params.jobId, TransferNotification.build(this, ready),
                    JOB_END_NOTIFICATION_POLICY_REMOVE)
        } catch (error: Exception) {
            cancelled.remove(params.jobId)
            ModelDownloadJournal.change(this, name) {
                it.copy(state = "failed", error = "Download-Benachrichtigung: " +
                    (error.message ?: error.javaClass.simpleName))
            }
            return false
        }
        executor.execute {
            var reschedule = false
            try {
                var lastUpdate = 0L
                ModelStore(applicationContext).download(
                    existing.url, name, existing.hash, flag,
                    onVerifying = {
                        ModelDownloadJournal.change(applicationContext, name) {
                            if (it.state == "running") it.copy(state = "verifying") else it
                        }
                    }
                ) { p ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 1200) {
                        lastUpdate = now
                        ModelDownloadJournal.change(applicationContext, name) {
                            // onStopJob or the user may have set queued/paused
                            // while the blocking input stream was closing.
                            if (it.state == "running") it.copy(done = p.done, total = p.total)
                            else it
                        }
                        ModelDownloadJournal.read(applicationContext, name)?.let {
                            TransferNotification.update(applicationContext, it)
                        }
                    }
                }
                ModelDownloadJournal.change(applicationContext, name) {
                    it.copy(state = "complete", error = "", url = "", hash = null,
                        done = java.io.File(ModelStore(applicationContext).dir, name).length())
                }
            } catch (_: TransferCancelled) {
                reschedule = !stopped.contains(params.jobId) &&
                    ModelDownloadJournal.read(this, name)?.state != "paused"
            } catch (error: Exception) {
                val attempts = existing.attempts + 1
                reschedule = attempts < 4 && error is IOException &&
                    ModelDownloadJournal.read(this, name)?.state != "paused"
                ModelDownloadJournal.change(applicationContext, name) {
                    it.copy(state = if (reschedule) "queued" else "failed",
                        error = error.message.orEmpty().take(180), attempts = attempts)
                }
            } finally {
                val latest = ModelDownloadJournal.read(applicationContext, name)
                if (latest != null && latest.state !in setOf("running", "queued"))
                    TransferNotification.update(applicationContext, latest, false)
                cancelled.remove(params.jobId)
                if (!stopped.contains(params.jobId)) jobFinished(params, reschedule)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped.add(params.jobId)
        cancelled.remove(params.jobId)?.set(true)
        val name = params.extras.getString("name") ?: return false
        val paused = ModelDownloadJournal.read(this, name)?.state == "paused"
        if (!paused) ModelDownloadJournal.change(this, name) {
            it.copy(state = "queued", lastStopReason = params.stopReason)
        }
        return !paused
    }

    override fun onDestroy() {
        cancelled.values.forEach { it.set(true) }
        executor.shutdown()
        super.onDestroy()
    }
}

class ModelTransferWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val name = inputData.getString("name") ?: return@withContext Result.failure()
        val entry = ModelDownloadJournal.read(applicationContext, name)
            ?: return@withContext Result.failure()
        if (entry.state == "paused") return@withContext Result.failure()
        val current = entry.copy(state = "running")
        ModelDownloadJournal.write(applicationContext, current)
        val cancelled = AtomicBoolean()
        var lastUpdate = 0L
        try {
            // Foreground-service restrictions and missing notifications must
            // be stored as a failure, never leave a permanent "running" state.
            setForeground(ForegroundInfo(current.id,
                TransferNotification.build(applicationContext, current),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            ModelStore(applicationContext).download(
                entry.url, name, entry.hash, cancelled,
                onVerifying = {
                    ModelDownloadJournal.change(applicationContext, name) {
                        if (it.state == "running") it.copy(state = "verifying") else it
                    }
                }
            ) { p ->
                if (isStopped) cancelled.set(true)
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 1200) {
                    lastUpdate = now
                    ModelDownloadJournal.change(applicationContext, name) {
                        if (it.state == "running") it.copy(done = p.done, total = p.total)
                        else it
                    }
                    ModelDownloadJournal.read(applicationContext, name)?.let {
                        TransferNotification.update(applicationContext, it)
                    }
                    setProgressAsync(Data.Builder()
                        .putLong("bytes", p.done).putLong("total", p.total).build())
                }
            }
            ModelDownloadJournal.change(applicationContext, name) {
                it.copy(state = "complete", url = "", hash = null,
                    done = java.io.File(ModelStore(applicationContext).dir, name).length())
            }
            ModelDownloadJournal.read(applicationContext, name)?.let {
                TransferNotification.update(applicationContext, it, false)
            }
            Result.success()
        } catch (problem: Exception) {
            val paused = ModelDownloadJournal.read(applicationContext, name)?.state == "paused"
            val retry = !paused && runAttemptCount < 3 &&
                (problem is IOException || problem is TransferCancelled)
            ModelDownloadJournal.change(applicationContext, name) {
                it.copy(state = when {
                    paused -> "paused"
                    retry -> "queued"
                    else -> "failed"
                }, error = problem.message.orEmpty().take(180))
            }
            if (retry) Result.retry() else Result.failure()
        }
    }
}
