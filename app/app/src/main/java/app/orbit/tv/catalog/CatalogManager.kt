package app.orbit.tv.catalog

import app.orbit.tv.BuildConfig
import app.orbit.tv.settings.Prefs
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

sealed interface CatalogState {
    data object Missing : CatalogState
    data class Ready(val dao: CatalogDao, val info: CatalogInfo) : CatalogState
    data class Error(val message: String) : CatalogState
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Downloading(val progress: Int) : UpdateState
    data object Updated : UpdateState
    data class Failed(val message: String) : UpdateState
}

class CatalogManager(
    private val filesDir: File,
    private val cacheDir: File,
    private val prefs: Prefs,
    okHttp: OkHttpClient,
) {
    private val versionClient = okHttp.newBuilder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val downloadClient = okHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()

    private val _state = MutableStateFlow<CatalogState>(CatalogState.Missing)
    val state: StateFlow<CatalogState> = _state

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    private val mutex = Mutex()
    private var baseUrl: String = BuildConfig.CATALOG_BASE_URL

    val catalogFile: File get() = File(filesDir, "catalog.db")

    fun setBaseUrl(url: String?) {
        if (url.isNullOrBlank()) return
        baseUrl = if (url.endsWith("/")) url else "$url/"
    }

    /** Opens the installed catalog immediately, then checks for a newer one. */
    suspend fun start() = mutex.withLock {
        if (_state.value is CatalogState.Ready) return@withLock
        val local = withContext(Dispatchers.IO) { CatalogDao.open(catalogFile) }
        _state.value = if (local != null) {
            CatalogState.Ready(local, local.info())
        } else {
            CatalogState.Missing
        }
        checkForUpdateLocked()
    }

    /** Manual retry from the error screen or Settings. */
    suspend fun retry() = mutex.withLock {
        if (_state.value !is CatalogState.Ready) {
            val local = withContext(Dispatchers.IO) { CatalogDao.open(catalogFile) }
            if (local != null) _state.value = CatalogState.Ready(local, local.info())
        }
        checkForUpdateLocked()
    }

    private suspend fun checkForUpdateLocked() {
        _update.value = UpdateState.Checking
        try {
            val version = fetchVersion()
            if (version == null) {
                Log.i(TAG, "catalog check: not modified")
                _update.value = UpdateState.Idle
                return
            }
            Log.i(TAG, "catalog check: server=${version.catalogVersion} minApp=${version.minAppVersion}")
            if (version.minAppVersion > BuildConfig.VERSION_CODE) {
                Log.w(TAG, "catalog needs app ${version.minAppVersion}, this is ${BuildConfig.VERSION_CODE}")
                _update.value = UpdateState.Failed(
                    "Catalog needs a newer Orbit app (v${version.minAppVersion}).",
                )
                return
            }
            val ready = _state.value as? CatalogState.Ready
            if (ready != null && ready.info.version == version.catalogVersion) {
                _update.value = UpdateState.Idle
                return
            }
            install(version)
        } catch (e: Exception) {
            val message = e.message ?: "Update check failed"
            Log.w(TAG, "catalog update failed: ${e.javaClass.simpleName}: $message")
            _update.value = UpdateState.Failed(message)
            if (_state.value !is CatalogState.Ready) {
                _state.value = CatalogState.Error(message)
            }
        }
    }

    private suspend fun fetchVersion(): VersionInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + "version.json")
            .apply { prefs.versionEtag?.let { header("If-None-Match", it) } }
            .build()
        versionClient.newCall(request).execute().use { response ->
            if (response.code == 304) return@withContext null
            if (!response.isSuccessful) {
                throw IllegalStateException("Catalog server returned HTTP ${response.code}")
            }
            response.header("ETag")?.let { prefs.versionEtag = it }
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty version.json")
            VersionInfo.parse(JSONObject(body))
        }
    }

    private suspend fun install(version: VersionInfo) = withContext(Dispatchers.IO) {
        _update.value = UpdateState.Downloading(0)
        val gzFile = File(cacheDir, "catalog-download.gz")
        val nextFile = File(cacheDir, "catalog-next.db")
        try {
            download(version, gzFile)
            _update.value = UpdateState.Downloading(100)

            val hash = sha256(gzFile)
            if (!hash.equals(version.sha256, ignoreCase = true)) {
                throw IllegalStateException("Catalog checksum mismatch")
            }
            gunzip(gzFile, nextFile)

            val next = CatalogDao.open(nextFile)
                ?: throw IllegalStateException("Downloaded catalog is not valid")
            val info = next.info()
            next.close()
            if (info.schema !in 1..2) {
                throw IllegalStateException("Catalog schema ${info.schema} is not supported")
            }

            swapIn(nextFile)
            val opened = CatalogDao.open(catalogFile)
                ?: throw IllegalStateException("Could not reopen the new catalog")
            val newInfo = opened.info()
            prefs.installedVersion = newInfo.version
            (_state.value as? CatalogState.Ready)?.dao?.close()
            _state.value = CatalogState.Ready(opened, newInfo)
            _update.value = UpdateState.Updated
        } catch (e: Exception) {
            nextFile.delete()
            val message = e.message ?: "Catalog install failed"
            _update.value = UpdateState.Failed(message)
            if (_state.value !is CatalogState.Ready) {
                _state.value = CatalogState.Error(message)
            }
        } finally {
            gzFile.delete()
        }
    }

    private fun download(version: VersionInfo, target: File) {
        target.parentFile?.mkdirs()
        val request = Request.Builder().url(baseUrl + "catalog.db.gz").build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Catalog download returned HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty catalog download")
            val total = body.contentLength().takeIf { it > 0 } ?: version.sizeBytes
            FileOutputStream(target).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastProgress = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val progress = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                _update.value = UpdateState.Downloading(progress)
                            }
                        }
                    }
                    out.fd.sync()
                }
            }
        }
        if (version.sizeBytes > 0 && target.length() != version.sizeBytes) {
            throw IllegalStateException("Catalog size mismatch")
        }
    }

    private fun gunzip(source: File, target: File) {
        target.delete()
        GZIPInputStream(FileInputStream(source)).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    written += read
                    if (written > MAX_UNCOMPRESSED) {
                        throw IllegalStateException("Catalog expands beyond the size limit")
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
    }

    private fun swapIn(nextFile: File) {
        val backup = File(filesDir, "catalog.db.bak")
        backup.delete()
        if (catalogFile.exists() && !catalogFile.renameTo(backup)) {
            throw IllegalStateException("Could not move the old catalog aside")
        }
        if (!nextFile.renameTo(catalogFile)) {
            backup.renameTo(catalogFile)
            throw IllegalStateException("Could not install the new catalog")
        }
        backup.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class VersionInfo(
        val catalogVersion: String,
        val sha256: String,
        val sizeBytes: Long,
        val minAppVersion: Int,
    ) {
        companion object {
            fun parse(json: JSONObject) = VersionInfo(
                catalogVersion = json.optString("catalog_version"),
                sha256 = json.optString("sha256"),
                sizeBytes = json.optLong("size_bytes", 0),
                minAppVersion = json.optInt("min_app_version", 1),
            )
        }
    }

    private companion object {
        const val TAG = "OrbitCatalog"
        const val MAX_UNCOMPRESSED = 512L * 1024 * 1024
    }
}
