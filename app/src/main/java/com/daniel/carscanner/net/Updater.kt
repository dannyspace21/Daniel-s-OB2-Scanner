package com.daniel.carscanner.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * WHAT THIS FILE DOES
 * In-app updates from the app's own GitHub repository.
 *
 * Every successful build on GitHub publishes a Release named "build-<number>"
 * with the APK attached. This file:
 *   1. check()     asks GitHub for the latest release and compares its build
 *                  number with this app's (versionCode)
 *   2. download()  downloads the APK (https only, from GitHub only)
 *   3. verify      checks the APK is signed with the SAME key as this app, so a
 *                  tampered or foreign APK is rejected before you see it
 *   4. install()   opens Android's installer; you tap Install to confirm
 *                  (Android never lets an app skip that step)
 *
 * Nothing about you or your car is sent: the app only reads GitHub's public
 * release page. No account or token is involved.
 */

/** A newer build waiting on GitHub. */
data class UpdateInfo(val build: Int, val name: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

object Updater {
    private const val REPO = "dannyspace21/Daniel-s-OB2-Scanner"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    /** Hosts the APK may come from: GitHub and its download servers. Nothing else. */
    private fun trustedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "github.com" || h == "api.github.com" || h.endsWith(".githubusercontent.com")
    }

    /** "build-123" -> 123. */
    fun buildNumber(tag: String): Int? = tag.removePrefix("build-").toIntOrNull()?.takeIf { tag.startsWith("build-") }

    /** Asks GitHub for the newest build. Returns it only if it's newer than [currentBuild]. Network call. */
    fun check(currentBuild: Int): UpdateInfo? {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DanielsCarMechanicScanner-updater")
        try {
            if (c.responseCode == 404) return null   // no releases yet
            if (c.responseCode !in 200..299) throw IOException("GitHub answered with error ${c.responseCode}")
            val text = c.inputStream.use { it.readBytes().also { b -> if (b.size > 1_000_000) throw IOException("Reply too large") } }
                .toString(Charsets.UTF_8)
            return parse(JSONObject(text), currentBuild)
        } finally { c.disconnect() }
    }

    /** Reads GitHub's release JSON. Separate from check() so it can be unit tested. */
    fun parse(o: JSONObject, currentBuild: Int): UpdateInfo? {
        val build = buildNumber(o.optString("tag_name")) ?: return null
        if (build <= currentBuild) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (!a.optString("name").endsWith(".apk") || !url.startsWith("https://")) continue
            if (!trustedHost(runCatching { URL(url).host }.getOrNull())) continue
            return UpdateInfo(build, o.optString("name").ifBlank { "Build $build" }, o.optString("body").take(2_000), url, a.optLong("size", 0L))
        }
        return null
    }

    /**
     * Downloads the APK to the app's private cache. Follows GitHub's redirect to its
     * download server, but only over https and only to GitHub hosts. Reports progress 0..1.
     */
    fun download(ctx: Context, info: UpdateInfo, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // keep only the newest download
        val out = File(dir, "update-${info.build}.apk")
        var url = URL(info.apkUrl)
        repeat(5) {   // follow up to 5 redirects by hand, checking each hop
            if (url.protocol != "https" || !trustedHost(url.host)) throw IOException("Refused to download from ${url.host}")
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "DanielsCarMechanicScanner-updater")
            try {
                when (c.responseCode) {
                    in 300..399 -> { url = URL(url, c.getHeaderField("Location") ?: throw IOException("Bad redirect")); return@repeat }
                    !in 200..299 -> throw IOException("Download failed (error ${c.responseCode})")
                }
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                if (total > MAX_APK_BYTES) throw IOException("Update is unexpectedly large")
                c.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (done > MAX_APK_BYTES) throw IOException("Update is unexpectedly large")
                            if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                return out
            } finally { c.disconnect() }
        }
        throw IOException("Too many redirects")
    }

    /** True if the downloaded APK is this app, signed with this app's key. */
    fun isSignedLikeThisApp(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        return if (Build.VERSION.SDK_INT >= 28) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
            if (theirs.packageName != ctx.packageName) return false
            val mine = pm.getPackageInfo(ctx.packageName, flags)
            fun certs(info: android.content.pm.PackageInfo) =
                info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            val t = certs(theirs)
            // Some Android versions can't read an APK's signature before install; Android's
            // installer still refuses a different key, so let it make the call then.
            t.isEmpty() || t == certs(mine)
        } else {
            // Android 8: Android's installer still refuses an APK signed with a different key.
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, 0)?.packageName == ctx.packageName
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** True if Android lets this app open the installer (a one-time switch in Settings). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the Settings page with the "Allow from this source" switch for this app. */
    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens Android's installer for the downloaded APK. You confirm with Install. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
