package com.jobcrawler.app

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * The notification's Apply button. No screen of its own: it copies the cover
 * note, opens the job — LinkedIn links open in the LinkedIn app — records the
 * application, and is gone. An activity rather than a receiver, because only
 * the app in front may write the clipboard.
 */
class ApplyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(this)

        val id = intent.getLongExtra(EXTRA_ROW, -1)
        val row = Store.shared(this).let { store ->
            store.findById(id)?.also { store.applications.record(it.item, "link") }
        }

        if (row == null) {
            Toast.makeText(this, "That job is no longer stored", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val note = CoverNote.text(row.item, ApplySettings.load(this), withCv = false)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Cover note", note))
        getSystemService(NotificationManager::class.java).cancel(id.toInt())
        Log.info("✋ applying: ${row.item.title}${row.item.company?.let { " @ $it" } ?: ""}")

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.item.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, "Cover note copied — paste it in the application", Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Nothing on the phone opens ${row.item.url}", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val EXTRA_ROW = "row"

        fun intent(context: Context, rowId: Long): Intent =
            Intent(context, ApplyActivity::class.java)
                .putExtra(EXTRA_ROW, rowId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
    }
}
