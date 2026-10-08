package com.tvphoto.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches a release APK and hands it to Android's own package installer.
 *
 * The app never installs anything itself: it downloads a file, proves it is the file the
 * release manifest described, and then opens the system's installer on it. On a
 * television that last step is a screen of its own - the user still has to confirm it
 * with the remote - and this is deliberate. A silent install would need system
 * privileges this app does not have and would not deserve.
 *
 * Verification is the reason the download is tied to a manifest at all. An APK fetched
 * because a version number looked right is an arbitrary program from the network being
 * offered to the installer; the SHA-256 in `version.json` is what makes it *this* build.
 * A release without one is never downloaded - see [UpdateRelease.isInstallable].
 */
class ApkInstaller(
    context: Context,
    private val client: OkHttpClient = downloadClient(),
) {

    private val appContext: Context = context.applicationContext

    sealed interface DownloadResult {
        /** The bytes on disk hash to what the manifest said. Ready for the installer. */
        data class Ready(val file: File) : DownloadResult

        /**
         * The download completed but is not the file the manifest described.
         *
         * Its own case rather than a message, because it is the one outcome that is a
         * reason to stop rather than to try again: something between here and the release
         * page served different bytes than the release published.
         */
        data object NotTheFile : DownloadResult

        /**
         * The bytes match the manifest, but the APK is signed by somebody else.
         *
         * This is the case a manifest is not sufficient protection against. A manifest
         * fetched over an untrusted path - a mirror, a plain-HTTP address on the local
         * network - can be replaced wholesale, hash and all, so a hash check alone only
         * proves the file matches what *that* manifest said. The signature is the one
         * thing an attacker cannot forge without the release key, and it is checked here,
         * after the bytes and before the installer.
         */
        data object NotSignedByUs : DownloadResult

        /** Nothing usable was produced; [reason] is for the log and the row's fallback text. */
        data class Failed(val reason: String) : DownloadResult
    }

    /**
     * Where an update is written.
     *
     * The app's own external directory, so the system installer can read the file
     * through the FileProvider below without any storage permission: on API 23 and up an
     * app-specific directory needs none, and this app declares none. Internal storage is
     * the fallback for a device that reports no external volume at all.
     */
    private val directory: File
        get() = (appContext.getExternalFilesDir(null) ?: appContext.filesDir).resolve(UPDATE_DIR)

    /**
     * Whether Android will let this app open an installer at all.
     *
     * Since Android 8 this is a per-app grant - "install unknown apps" - and it is the
     * one step of this feature a television remote cannot be talked through casually, so
     * the row asks before spending 66 MB on a download nobody can install.
     */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            // Before Android 8 there is no per-app grant: the device has a single
            // unknown-sources switch and the installer enforces it itself.
            true
        }

    /** The system screen where that grant is given, for the row to open. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
            // Launched from the application context, like the installer handoff below.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Downloads [release] into [directory], hashing as it goes, and only reports [Ready]
     * when the digest matches the manifest.
     *
     * [onProgress] is called with 0..100, at most once per whole percent. It is not
     * called when the server declines to state a length - a progress bar that is
     * guessing is worse than none on a screen nobody can scroll.
     */
    suspend fun download(
        release: UpdateRelease,
        onProgress: (Int) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        val url = release.apkUrl
        val expected = release.sha256
        if (url.isNullOrBlank() || expected.isNullOrBlank()) {
            return@withContext DownloadResult.Failed("release carries no apkUrl or sha256")
        }

        val target = directory.resolve(release.apkFileName())
        val partial = directory.resolve("${release.apkFileName()}.part")
        runCatching { directory.mkdirs() }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            // Fed as the bytes arrive rather than run over the file afterwards: the file
            // is 66 MB and this is a television, so the digest is the one thing here that
            // should not need a second pass.
            val digest = MessageDigest.getInstance("SHA-256")

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadResult.Failed("download answered ${response.code}")
                }
                val body = response.body ?: return@withContext DownloadResult.Failed("empty response")
                val total = body.contentLength()
                var written = 0L
                var lastPercent = -1

                partial.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            }

            val actual = digest.digest().toHex()
            if (!actual.equals(expected, ignoreCase = true)) {
                partial.delete()
                // Never kept, not even renamed aside: a file that failed this check is
                // exactly the thing the check exists to keep away from the installer.
                return@withContext DownloadResult.NotTheFile
            }

            // The hash proves this is the file the manifest described. The signature proves
            // the manifest was describing *our* app - and it is the only one of the two an
            // attacker cannot produce, which is what lets the manifest itself come from a
            // mirror.
            when (val verdict = signerVerdict(partial)) {
                SignerVerdict.Same -> Unit

                SignerVerdict.Different -> {
                    partial.delete()
                    return@withContext DownloadResult.NotSignedByUs
                }

                // Cannot be read, so it cannot be called wrong. The installer checks the
                // same thing before it replaces anything, so the file is allowed to reach
                // it - refusing here would block a genuine update on whatever device could
                // not parse the archive, which is a worse failure than the one it guards.
                SignerVerdict.Unreadable -> {
                    Log.w(TAG, "could not read the update's signing certificate; leaving it to the installer")
                }
            }

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext DownloadResult.Failed("could not move the download into place")
            }
            DownloadResult.Ready(target)
        } catch (error: Throwable) {
            runCatching { partial.delete() }
            DownloadResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    /**
     * The verified APK already on disk for [release], if a previous attempt got that far.
     *
     * Exists so that a second launch - the user cancelled on the installer's own screen,
     * or it refused and they have since granted the permission - does not fetch 66 MB
     * again to reach a file that is already sitting there.
     */
    fun downloadedFile(release: UpdateRelease): File? =
        directory.resolve(release.apkFileName()).takeIf { it.isFile }

    /**
     * Whether [file] carries the same signing certificate as the app already installed.
     *
     * Android itself refuses to lay an APK signed with a different key over an existing
     * app, so this is not the guarantee - the installer is. It is checked here for the two
     * things the installer cannot give: a clear refusal instead of a cryptic one on a
     * television screen, and the certainty that the file never reaches the installer at
     * all. A file that is definitely somebody else's is deleted, not kept for a second
     * opinion.
     *
     * Only a definite mismatch refuses. Not being able to read the archive's certificate
     * is *not* the same answer as the certificate being wrong, and treating the two alike
     * is not theoretical: it is what this did on a real television, where it refused a
     * genuine update and said the signature was wrong. The installer still enforces the
     * rule either way, so an unreadable archive is allowed through and logged.
     */
    private fun signerVerdict(file: File): SignerVerdict {
        val ours = ourSignerDigest
        if (ours == null) {
            Log.w(TAG, "cannot read this app's own signing certificate")
            return SignerVerdict.Unreadable
        }
        val theirs = archiveSignerDigest(file)
        if (theirs == null) {
            Log.w(TAG, "cannot read the downloaded APK's signing certificate")
            return SignerVerdict.Unreadable
        }
        if (ours.contentEquals(theirs)) return SignerVerdict.Same

        // Both digests, because these two lines are the whole evidence for a refusal and
        // reading them out is the only way anyone finds out what actually differed.
        Log.w(
            TAG,
            "update is signed by ${theirs.toHex().take(16)}..., this app by ${ours.toHex().take(16)}...",
        )
        return SignerVerdict.Different
    }

    private enum class SignerVerdict { Same, Different, Unreadable }

    /** The certificate [file] is signed with, or null when it cannot be read. */
    private fun archiveSignerDigest(file: File): ByteArray? {
        val info = runCatching {
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
        }.getOrNull() ?: return null
        return signersOf(info).firstOrNull()?.toByteArray()?.sha256()
    }

    /**
     * This install's own signing certificate, as a digest.
     *
     * Read once and remembered: it is a property of the installed app, and it cannot change
     * while this process is alive - an install that replaced it would have replaced the
     * process too.
     */
    private val ourSignerDigest: ByteArray? by lazy {
        runCatching {
            val info = appContext.packageManager
                .getPackageInfo(appContext.packageName, signingFlags())
            signersOf(info).firstOrNull()?.toByteArray()?.sha256()
        }.getOrNull()
    }

    /**
     * Both signing flags, always, and the older one is the load-bearing half.
     *
     * `getPackageArchiveInfo` walks an APK's certificates only when [PackageManager.GET_SIGNATURES]
     * is among the flags - pass `GET_SIGNING_CERTIFICATES` alone and it still returns a
     * `PackageInfo`, but one whose signing details were never collected. That asymmetry does
     * not show up on the installed package, whose signing details come from the package
     * manager's own database, which is exactly why it took a television to find it.
     */
    private fun signingFlags(): Int =
        PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES

    /**
     * The certificates an APK declares.
     *
     * `signingInfo` is the current API and the only one that describes a rotated or
     * multi-signer package properly, but it arrived in Android 9 - and this app installs
     * from Android 6 - so the older field is the one that answers there. On Android 9 and
     * up the older field is still read when the newer one comes back empty, because an
     * empty answer means "not collected", not "not signed", and the whole point of this
     * code is not to confuse the two.
     */
    private fun signersOf(info: PackageInfo?): List<Signature> {
        if (info == null) return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val modern = info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            if (modern.isNotEmpty()) return modern
        }
        @Suppress("DEPRECATION")
        return info.signatures?.toList().orEmpty()
    }

    /**
     * Opens the system installer on a file this class produced.
     *
     * Returns false when no activity would take the intent - a television whose firmware
     * has no package installer reachable this way. The settings row turns that into the
     * manual route rather than a dead button.
     */
    fun launchInstaller(file: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}$FILE_PROVIDER_SUFFIX", file)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Started from the application context: without this flag Android refuses
            // the launch outright.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent) }.isSuccess
    }

    companion object {
        private const val UPDATE_DIR = "updates"

        /** Names this class's lines in the log, where a television has no console. */
        private const val TAG = "ApkInstaller"

        /** Matches the authority declared in AndroidManifest.xml. */
        private const val FILE_PROVIDER_SUFFIX = ".updates"

        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        private const val USER_AGENT = "FN-Photo-TV"

        private const val BUFFER_BYTES = 64 * 1024

        /**
         * The name an update lands under, derived from the release rather than fixed, so
         * that a leftover file from an earlier attempt is recognisable as one.
         */
        private fun UpdateRelease.apkFileName(): String =
            "FN-tvphoto-${versionName.filter { it.isLetterOrDigit() || it == '.' || it == '-' }}.apk"

        /**
         * A client of its own, and not the checker's.
         *
         * That one carries a 30-second call timeout, which is right for a request whose
         * whole answer is a few hundred bytes and wrong for a 66 MB APK on a television
         * sharing a wireless link: the call would be abandoned partway through a download
         * that was progressing perfectly well. There is no call timeout here at all -
         * only per-read ones - so a slow link takes as long as it takes.
         */
        fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        /** Certificates are compared by digest: the byte arrays themselves are DER blobs. */
        private fun ByteArray.sha256(): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(this)
    }
}
