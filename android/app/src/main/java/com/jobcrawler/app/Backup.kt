package com.jobcrawler.app

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A copy of the database in a folder the person picked, outside the app.
 *
 * Uninstalling deletes everything the app keeps for itself: which jobs were
 * already sent, which addresses were emailed. A copy in Documents survives
 * that. After a reinstall, picking the same folder brings it all back, so the
 * first run carries on where the old app stopped instead of starting over.
 *
 * Android forgets the folder permission on uninstall too; picking the folder
 * again is the one step a reinstall needs.
 */
object Backup {
    private const val PREFS = "backup"
    const val FILE = "JobCrawler-backup.sqlite"

    fun folder(context: Context): Uri? {
        val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tree", null)?.let(Uri::parse)
            ?: return null
        return uri.takeIf { context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } }
    }

    fun lastSaved(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lastSaved", null)

    private fun markSaved(context: Context) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lastSaved", time).apply()
    }

    fun setFolder(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tree", tree.toString()).apply()
    }

    /** The backup file already in the folder, if there is one. */
    fun existing(context: Context, tree: Uri): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                // Some file pickers add their own extension to a new file's name.
                if (cursor.getString(1)?.startsWith(FILE.substringBefore('.')) == true) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
            null
        }
    }

    /**
     * Copies the database out. Called with the run's own store still open, so
     * pending writes are folded into the main file first.
     *
     * @return an error to show, or null when saved.
     */
    fun save(context: Context, store: Store): String? {
        val tree = folder(context) ?: return "No backup folder chosen"
        return try {
            store.checkpoint()
            val resolver = context.contentResolver
            val target = existing(context, tree) ?: DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                "application/octet-stream",
                FILE,
            ) ?: return "Could not create $FILE in the backup folder"

            resolver.openOutputStream(target, "wt")?.use { out ->
                context.getDatabasePath(Store.NAME).inputStream().use { it.copyTo(out) }
            } ?: return "Could not write to the backup folder"

            markSaved(context)
            null
        } catch (error: Exception) {
            "Backup failed: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    /**
     * Replaces the database with a backup. Never during a crawl: the run's
     * open connection would keep writing to the file being replaced.
     *
     * @return an error to show, or null when restored.
     */
    fun restore(context: Context, source: Uri): String? {
        if (Scheduler.isRunning(context)) return "A run is in progress; restore once it ends"

        val temp = File(context.cacheDir, "restore.sqlite")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: return "Could not read the backup"

            val header = temp.inputStream().use { input -> ByteArray(15).also { input.read(it) } }
            if (String(header, Charsets.US_ASCII) != "SQLite format 3") return "That file is not a JobCrawler backup"

            val rows = runCatching {
                SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT COUNT(*) FROM items", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                }
            }.getOrElse { return "That file is not a JobCrawler backup" }

            // The workers' shared connection still points at the old file: close it, the next run opens the new one.
            Store.closeShared()
            val database = context.getDatabasePath(Store.NAME)
            database.parentFile?.mkdirs()
            for (suffix in listOf("-wal", "-shm", "-journal")) File(database.path + suffix).delete()
            temp.copyTo(database, overwrite = true)
            // Counts as saved: this install now holds the backup's history, and may overwrite it.
            markSaved(context)
            Log.info("Database restored from backup: $rows jobs")
            return null
        } catch (error: Exception) {
            return "Restore failed: ${error.javaClass.simpleName}: ${error.message}"
        } finally {
            temp.delete()
        }
    }
}
