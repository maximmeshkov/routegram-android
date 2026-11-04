package com.routegram.wsproxy

import android.content.Context
import android.os.SystemClock
import com.routegram.core.ProxyConfig
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipInputStream

internal interface PythonLibrary : Library {
    fun RoutegramPythonInit(home: String, stdlib: String, packages: String, nativeDir: String): Pointer?
    fun RoutegramPythonCall(request: String): Pointer?
    fun RoutegramPythonFree(value: Pointer)
}

/** Owns one embedded interpreter and the upstream proxy's start/stop lifecycle. */
object WsProxyNative {
    private var library: PythonLibrary? = null
    private var initialized = false

    @Synchronized
    fun start(context: Context, settings: WsProxySettings): ProxyConfig {
        initialize(context.applicationContext)
        stop()
        val supplied = settings.secret.ifEmpty {
            ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()
        }
        val secret = if (supplied.length == 34 && supplied.startsWith("dd")) supplied.substring(2) else supplied
        require(secret.matches(Regex("[0-9a-fA-F]{32}"))) { "Invalid MTProto secret" }
        call(JSONObject().put("operation", "start").put("settings", JSONObject()
            .put("host", settings.bindIp).put("port", settings.port)
            .put("secret", secret).put("dc_ips", settings.dcIps)
            .put("pool_size", settings.poolSize).put("cf_enabled", settings.cfEnabled)
            .put("cf_domains", settings.cfDomain).put("verbose", settings.verbose)))
        val deadline = SystemClock.elapsedRealtime() + 15_000
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                val status = call(JSONObject().put("operation", "status"))
                check(status.isNull("error")) { status.optString("error") }
                if (status.optBoolean("ready")) {
                    return ProxyConfig(settings.bindIp, settings.port, secret = "dd$secret")
                }
                check(status.optBoolean("running")) { "Flowseal exited before opening its listener" }
                Thread.sleep(50)
            }
            error("Flowseal listener startup timed out")
        } catch (error: Throwable) {
            runCatching { stop() }
            throw error
        }
    }

    @Synchronized
    fun stop() {
        if (initialized) call(JSONObject().put("operation", "stop"))
    }

    @Synchronized
    fun diagnostics(context: Context): JSONObject {
        initialize(context.applicationContext)
        return call(JSONObject().put("operation", "diagnostics"))
    }

    private fun initialize(context: Context) {
        if (initialized) return
        val runtime = installRuntime(context)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        check(File(nativeDir, "libpython3.13.so").isFile) {
            "Python native libraries were not extracted from the APK"
        }
        val loaded = library ?: (Native.load(File(nativeDir, "libroutegram_python.so").absolutePath,
            PythonLibrary::class.java) as PythonLibrary).also { library = it }
        val failure = loaded.RoutegramPythonInit(runtime.absolutePath,
            File(runtime, "stdlib.zip").absolutePath,
            File(runtime, "packages").absolutePath, nativeDir)
        if (failure != null) {
            val message = try { failure.getString(0, "UTF-8") }
            finally { loaded.RoutegramPythonFree(failure) }
            error("Python initialization failed: $message")
        }
        // Keep the library loaded for the lifetime of the interpreter/worker threads.
        initialized = true
    }

    private fun call(request: JSONObject): JSONObject {
        val api = checkNotNull(library)
        val pointer = checkNotNull(api.RoutegramPythonCall(request.toString())) {
            "Python returned no response"
        }
        val result = try { JSONObject(pointer.getString(0, "UTF-8")) }
        finally { api.RoutegramPythonFree(pointer) }
        check(result.optBoolean("ok")) { result.optString("error", "Python operation failed") }
        return result
    }

    private fun installRuntime(context: Context): File {
        val manifestBytes = context.assets.open("routegram-python/manifest.json").use { it.readBytes() }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(manifestBytes).hex()
        val directory = File(context.noBackupFilesDir, "flowseal/$fingerprint")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create Python runtime directory" }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8)).getJSONObject("files")
        val marker = File(directory, ".complete")
        if (marker.isFile && File(directory, "stdlib.zip").isFile &&
            File(directory, "packages/routegram_runtime.py").isFile) return directory

        for (name in manifest.keys()) {
            require(name == File(name).name) { "Invalid runtime asset name" }
            val target = File(directory, name)
            context.assets.open("routegram-python/$name").use { source ->
                target.outputStream().use { source.copyTo(it) }
            }
            val hash = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            check(hash.digest().hex() == manifest.getString(name)) { "Runtime checksum mismatch: $name" }
        }
        val packages = File(directory, "packages")
        check(packages.isDirectory || packages.mkdirs())
        ZipInputStream(File(directory, "packages.zip").inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(packages, entry.name)
                require(target.canonicalPath.startsWith(packages.canonicalPath + File.separator))
                if (entry.isDirectory) target.mkdirs()
                else {
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        marker.writeText(fingerprint)
        return directory
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}
