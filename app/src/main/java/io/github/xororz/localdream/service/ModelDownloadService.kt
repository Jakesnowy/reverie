package io.github.xororz.localdream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DownloadPin
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.utils.DownloadIntegrity
import io.github.xororz.localdream.utils.Http
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var downloadJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val client = Http.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val generationPreferences by lazy { GenerationPreferences(this) }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2001

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState

        const val ACTION_START_DOWNLOAD = "action_start_download"
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"

        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_FILE_URL = "file_url"
        const val EXTRA_IS_ZIP = "is_zip"
        const val EXTRA_IS_NPU = "is_npu"
        const val EXTRA_MODEL_TYPE = "model_type" // "sd" or "upscaler"
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val modelId: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadState()

        data class Extracting(val modelId: String) : DownloadState()
        data class Success(val modelId: String) : DownloadState()
        data class Error(val modelId: String, val message: String) : DownloadState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelId
                val fileUrl = intent.getStringExtra(EXTRA_FILE_URL) ?: return START_NOT_STICKY
                val isZip = intent.getBooleanExtra(EXTRA_IS_ZIP, false)
                val isNpu = intent.getBooleanExtra(EXTRA_IS_NPU, false)
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: "sd"

                startForeground(NOTIFICATION_ID, createNotification(modelName, 0f))
                startDownload(modelId, modelName, fileUrl, isZip, isNpu, modelType)
            }

            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownload()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(
        modelId: String,
        modelName: String,
        fileUrl: String,
        isZip: Boolean,
        isNpu: Boolean,
        modelType: String,
    ) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            var tempFile: File? = null
            var extractTempDir: File? = null
            try {
                _downloadState.value = DownloadState.Downloading(modelId, 0f, 0, 0)

                val tempDir = File(filesDir, "temp_downloads")

                // Clean stale temp files from earlier runs, but keep this
                // model's own partial download so a retry can resume it.
                if (tempDir.exists()) {
                    tempDir.listFiles()?.forEach {
                        if (it.name != "$modelId.tmp") it.deleteRecursively()
                    }
                }
                tempDir.mkdirs()

                // Deterministic name so downloadFile can find (and resume
                // from) the partial file of a previous attempt.
                tempFile = File(tempDir, "$modelId.tmp")

                downloadFile(fileUrl, tempFile, modelId, modelName)

                when (modelType) {
                    "sd" -> {
                        if (isZip) {
                            val modelDir = File(getModelsDir(), modelId)

                            if (modelDir.exists()) {
                                modelDir.deleteRecursively()
                            }
                            modelDir.mkdirs()

                            extractTempDir = File(tempDir, "${modelId}_extract")
                            extractTempDir.mkdirs()

                            _downloadState.value = DownloadState.Extracting(modelId)
                            updateNotification(modelName, 0f, isExtracting = true)

                            unzipFile(tempFile, extractTempDir)

                            extractTempDir.listFiles()?.forEach { file ->
                                file.renameTo(File(modelDir, file.name))
                            }
                            extractTempDir.delete()
                            extractTempDir = null

                            if (isNpu) {
                                File(modelDir, "v3").createNewFile()
                            }
                        }
                    }

                    "upscaler" -> {
                        val upscalerDir = File(getModelsDir(), modelId).apply {
                            if (!exists()) mkdirs()
                        }
                        val targetFile = File(upscalerDir, Model.UPSCALER_FILE_NAME)

                        if (targetFile.exists()) {
                            targetFile.delete()
                        }

                        // Don't report success on a failed move: it would leave
                        // an empty model dir that the UI/loader can't use.
                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                        }
                    }
                }

                tempFile.delete()
                tempFile = null

                _downloadState.value = DownloadState.Success(modelId)
                updateNotification(modelName, 100f, true)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: CancellationException) {
                // Cancellation (service reclaimed, a new download started, or
                // explicit cancel) is not a download failure: re-throw so it is
                // not surfaced as an "Error" state. Emitting Error here is what
                // produced the spurious "Job was cancelled" snackbar that could
                // appear right after a successful download finished.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)

                // Keep the partial .tmp file on disk: the next download
                // attempt for the same model resumes from it (see
                // downloadFile). Extract leftovers are useless though.
                extractTempDir?.deleteRecursively()
                tempFile = null

                _downloadState.value =
                    DownloadState.Error(modelId, e.message ?: getString(R.string.unknown_error))
                updateNotification(modelName, 0f, false, e.message)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(3000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        destFile: File,
        modelId: String,
        modelName: String,
    ): Unit = withContext(Dispatchers.IO) {
        // Integrity (utils/DownloadIntegrity + the design notes): HF sources
        // publish the authoritative SHA-256 + size via the resolve response
        // headers; other sources fall back to a TOFU pin from the first
        // successful install. Either way the assembled archive must match
        // before it is extracted, so a resume splice, upstream drift or MITM
        // cannot install corrupted weights.
        val authoritative = DownloadIntegrity.probeExpected(client, url)
        val pin = generationPreferences.getDownloadPin(url)
        val expected = authoritative
            ?: pin?.let { DownloadIntegrity.Expected(it.sha256, it.size) }

        var resumeFrom = if (destFile.exists()) destFile.length() else 0L
        // A partial larger than the expected archive can never be right
        // (changed upstream file or a stale pin): discard and restart.
        if (expected != null && resumeFrom > expected.size) {
            destFile.delete()
            resumeFrom = 0L
        }
        val request = Request.Builder()
            .url(url)
            .apply {
                if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
            }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                // The stored partial file no longer matches what the server
                // has (416 Range Not Satisfiable): discard it and start over.
                response.code == 416 -> {
                    destFile.delete()
                    return@withContext downloadFile(url, destFile, modelId, modelName)
                }
                response.code != 200 && response.code != 206 && !response.isSuccessful -> {
                    throw Exception(
                        getString(R.string.error_download_failed, response.code.toString()),
                    )
                }
            }

            // 206 Partial Content = the server honored the Range request.
            val resuming = response.code == 206 && resumeFrom > 0
            if (!resuming && resumeFrom > 0) {
                // Server ignored the Range header; restart from scratch.
                destFile.delete()
            }

            val body = response.body ?: throw Exception("Response body is null")
            val contentLength = body.contentLength().takeIf { it > 0 }
            val totalBytes = when {
                contentLength != null && resuming -> contentLength + resumeFrom
                contentLength != null -> contentLength
                expected != null -> expected.size
                else -> 0L
            }
            // Exact expected size: reject a mismatch before transferring
            // anything - a server that swapped the archive underneath a
            // resume would otherwise waste the transfer and fail the digest
            // check afterwards anyway.
            if (expected != null && totalBytes > 0 && totalBytes != expected.size) {
                destFile.delete()
                throw DownloadIntegrity.IntegrityException(
                    getString(R.string.error_integrity_failed),
                )
            }
            var downloadedBytes = if (resuming) resumeFrom else 0L
            var lastUpdateTime = 0L
            val digest = DownloadIntegrity.newDigest()

            java.io.BufferedOutputStream(FileOutputStream(destFile, resuming)).use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var bytes: Int

                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        digest.update(buffer, 0, bytes)
                        downloadedBytes += bytes

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 500 ||
                            (totalBytes > 0 && downloadedBytes >= totalBytes)
                        ) {
                            lastUpdateTime = currentTime
                            val progress = if (totalBytes > 0) {
                                downloadedBytes.toFloat() / totalBytes
                            } else {
                                0f
                            }

                            _downloadState.value = DownloadState.Downloading(
                                modelId,
                                progress,
                                downloadedBytes,
                                totalBytes,
                            )

                            updateNotification(modelName, progress)
                        }
                    }
                }
            }

            // Guard against silently truncated downloads: a dropped connection
            // ends the read loop without throwing, leaving a partial file
            // that the next attempt resumes from.
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw Exception(
                    getString(R.string.error_download_failed, "$downloadedBytes/$totalBytes"),
                )
            }

            // Integrity: the assembled archive must match the authoritative
            // hash (HF headers) or the recorded pin. A mismatch is never
            // extracted - discard the partial so a retry starts clean.
            // On a resumed download the incremental digest only covers this
            // session's tail bytes, so re-hash the assembled file from disk
            // to compare against the whole-archive expectation. (Also keeps
            // the TOFU pin below a true full-file hash: a tail-only pin would
            // poison every future download of this URL.)
            val actualSha256 = if (resuming) {
                val fullDigest = DownloadIntegrity.newDigest()
                destFile.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        fullDigest.update(buffer, 0, n)
                    }
                }
                DownloadIntegrity.toHex(fullDigest)
            } else {
                DownloadIntegrity.toHex(digest)
            }
            if (expected != null && !actualSha256.equals(expected.sha256, ignoreCase = true)) {
                destFile.delete()
                throw DownloadIntegrity.IntegrityException(
                    getString(R.string.error_integrity_failed),
                )
            }
            if (authoritative == null) {
                // No authoritative source for this URL: pin this successful
                // download so future attempts and resumes are protected.
                generationPreferences.saveDownloadPin(
                    url,
                    DownloadPin(size = downloadedBytes, sha256 = actualSha256),
                )
            }
        }
    }

    private suspend fun unzipFile(zipFile: File, destDir: File) = withContext(Dispatchers.IO) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry

            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isNotEmpty() && !fileName.startsWith(".") && !fileName.startsWith("__MACOSX")) {
                        val file = File(destDir, fileName)

                        java.io.BufferedOutputStream(FileOutputStream(file)).use { output ->
                            zis.copyTo(output)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getModelsDir(): File = File(filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.model_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.model_download_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        modelName: String,
        progress: Float,
        isExtracting: Boolean = false,
    ): android.app.Notification {
        val title = if (isExtracting) {
            getString(R.string.extracting)
        } else {
            getString(R.string.downloading_model, modelName)
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), isExtracting)
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .build()
    }

    private fun updateNotification(
        modelName: String,
        progress: Float,
        success: Boolean = false,
        error: String? = null,
        isExtracting: Boolean = false,
    ) {
        val notification = when {
            success -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_complete))
                    .setContentText(modelName)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(false)
                    .build()
            }

            error != null -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_failed))
                    .setContentText(error)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(false)
                    .build()
            }

            else -> {
                createNotification(modelName, progress, isExtracting)
            }
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout(0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout(fgsType)
    }

    private fun handleTimeout(fgsType: Int) {
        Log.e(TAG, "Foreground service timeout (fgsType=$fgsType)")
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Error("timeout", "Foreground service timeout")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
