package com.jobcrawler.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app updating itself from the Google Drive folder CI uploads every
 * build to (android.yml, "Upload to Google Drive"), so a new build is a tap
 * here instead of a trip through Drive and the Files app.
 *
 * CI names each APK JobCrawler-1.0.<run>.apk and builds it with versionCode
 * <run>, so the number in the newest file's name is all a check needs. The
 * folder is read the way a browser reads a link-shared folder, with no
 * Google sign-in and no API key: it has to be shared as "Anyone with the
 * link". A private folder answers with a sign-in page, which [check] reports.
 */
object Updater {
    /** The same folder as DRIVE_FOLDER in .github/workflows/android.yml. */
    const val FOLDER_ID = "YOUR_DRIVE_FOLDER_ID"

    /** How often a crawl run looks; a check is one small page. */
    private const val CHECK_EVERY_MS = 30 * 60_000L

    private const val CHANNEL = "update"
    private const val NOTIFICATION_ID = 0x0dd

    private val NAME = Regex("""^JobCrawler-\d+\.\d+\.(\d+)\.apk$""")

    data class Release(val versionCode: Int, val name: String, val fileId: String) {
        val versionName get() = name.removePrefix("JobCrawler-").removeSuffix(".apk")
    }

    /** What the last check found, so a screen can show it without the network. */
    data class Status(val latest: Release?, val checkedAt: String?) {
        val updateReady get() = latest != null && latest.versionCode > BuildConfig.VERSION_CODE
    }

    /** The APKs a folder page lists, newest first. Pure, for the tests. */
    fun parseFolder(html: String): List<Release> {
        val page = Jsoup.parse(html)
        val found = page.select("div.flip-entry").mapNotNull { entry ->
            val name = entry.selectFirst(".flip-entry-title")?.text()?.trim() ?: return@mapNotNull null
            val id = entry.id().removePrefix("entry-").ifEmpty {
                entry.selectFirst("a[href]")?.attr("href")?.let(::fileIdOf) ?: ""
            }
            release(name, id)
        }.ifEmpty {
            // Not the layout above: any link to a file, named by its text.
            page.select("a[href*=/file/d/]").mapNotNull { link -> release(link.text().trim(), fileIdOf(link.attr("href")) ?: "") }
        }
        return found.distinctBy { it.fileId }.sortedByDescending { it.versionCode }
    }

    private fun release(name: String, id: String): Release? {
        val run = NAME.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (id.isEmpty()) return null
        return Release(run, name, id)
    }

    private fun fileIdOf(href: String) = Regex("""/file/d/([\w-]+)""").find(href)?.groupValues?.get(1)

    fun status(context: Context): Status {
        val prefs = prefs(context)
        val code = prefs.getInt("latestCode", -1)
        val latest = if (code < 0) null else Release(code, prefs.getString("latestName", "")!!, prefs.getString("latestId", "")!!)
        return Status(latest, prefs.getString("checkedAt", null))
    }

    /**
     * Reads the folder and remembers the newest APK in it. Blocks: call it
     * off the main thread.
     * @throws IOException with a sentence for the person when the folder can't be read
     */
    fun check(context: Context): Status {
        val url = "https://drive.google.com/embeddedfolderview?id=$FOLDER_ID"
        val html = GuestClient.shared.newCall(Request.Builder().url(url).header("User-Agent", Limits.USER_AGENT).build())
            .execute().use { response ->
                if (response.code == 401 || response.code == 403 || "accounts.google.com" in response.request.url.host) {
                    throw IOException("The Drive folder isn't shared: set it to \"Anyone with the link\"")
                }
                if (!response.isSuccessful) throw IOException("Drive answered HTTP ${response.code}")
                response.body?.string() ?: ""
            }

        val latest = parseFolder(html).firstOrNull()
            ?: throw IOException("No JobCrawler APK in the Drive folder")
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        prefs(context).edit()
            .putInt("latestCode", latest.versionCode)
            .putString("latestName", latest.name)
            .putString("latestId", latest.fileId)
            .putString("checkedAt", time)
            .putLong("checkedAtMs", System.currentTimeMillis())
            .apply()
        return Status(latest, time)
    }

    /**
     * After a crawl run: look again if it has been a while, and say once per
     * build that a newer one is waiting. Never throws; a failed check waits
     * for the next run.
     */
    fun checkInBackground(context: Context) {
        val prefs = prefs(context)
        if (System.currentTimeMillis() - prefs.getLong("checkedAtMs", 0) < CHECK_EVERY_MS) return
        val status = try {
            check(context)
        } catch (error: Exception) {
            Log.warn("Update check: ${error.message}")
            return
        }
        val latest = status.latest ?: return
        if (!status.updateReady || prefs.getInt("notifiedCode", -1) == latest.versionCode) return

        Log.info("Update ${latest.versionName} is on Drive (this is ${BuildConfig.VERSION_NAME})")
        notifyReady(context, latest)
        prefs.edit().putInt("notifiedCode", latest.versionCode).apply()
    }

    private fun notifyReady(context: Context, release: Release) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            SettingsActivity.intent(context).putExtra(SettingsActivity.EXTRA_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Update ${release.versionName} is ready")
            .setContentText("Tap to install it (this is ${BuildConfig.VERSION_NAME})")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** Whether the person has let this app install APKs (Settings → Install unknown apps). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /**
     * Downloads [release] and hands it to the system installer, which asks
     * the person to confirm. Blocks: call it off the main thread. The app is
     * replaced, and its process killed, once the person taps Update there.
     */
    fun install(context: Context, release: Release) {
        val apk = download(context, release)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                // Once this app installed the last build, Android 12+ lets it update itself without a prompt.
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Mutable: the installer fills in the status and its confirmation screen.
                val done = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                )
                session.commit(done.intentSender)
            }
            Log.info("Update ${release.versionName} handed to the installer")
        } finally {
            apk.delete()
        }
    }

    private fun download(context: Context, release: Release): File {
        // confirm=t skips Drive's "can't scan this file for viruses" page.
        val url = "https://drive.usercontent.google.com/download?id=${release.fileId}&export=download&confirm=t"
        val file = File(context.cacheDir, "update.apk")
        GuestClient.shared.newCall(Request.Builder().url(url).header("User-Agent", Limits.USER_AGENT).build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IOException("Drive answered HTTP ${response.code} for ${release.name}")
                val body = response.body ?: throw IOException("Drive sent nothing for ${release.name}")
                if (body.contentType()?.subtype == "html") {
                    throw IOException("Drive sent a page, not the APK: is the folder shared with \"Anyone with the link\"?")
                }
                file.outputStream().use { body.byteStream().copyTo(it) }
            }
        // An APK is a zip; anything else would only fail later inside the installer.
        val zip = file.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
        if (zip[0] != 'P'.code.toByte() || zip[1] != 'K'.code.toByte()) {
            file.delete()
            throw IOException("${release.name} from Drive is not an APK")
        }
        return file
    }

    private fun prefs(context: Context) = context.getSharedPreferences("update", Context.MODE_PRIVATE)
}

/** The installer's answer: show its confirmation screen, or log why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.init(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.info("Update installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.warn("Update not installed (status $status): $message")
                android.widget.Toast.makeText(context, "Update not installed: ${message ?: "status $status"}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
