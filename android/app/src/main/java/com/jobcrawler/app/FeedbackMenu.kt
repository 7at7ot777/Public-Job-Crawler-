package com.jobcrawler.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/**
 * Is this job a fit? The one menu both the job's screen and a long press in
 * the list open: a verdict the score learns from, or a mute that the filters
 * apply from the next run on (and the list hides at once).
 */
object FeedbackMenu {
    sealed class Change {
        /** [value] is [Feedback.UP], [Feedback.DOWN] or null (cleared). */
        data class Verdict(val value: String?) : Change()
        data object Muted : Change()
    }

    fun show(activity: Activity, row: Row, onDone: (Change) -> Unit) {
        val company = row.item.company?.takeIf { it.isNotBlank() }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += mark(row, Feedback.UP, "👍 Good fit") to { verdict(activity, row, Feedback.UP, onDone) }
        options += mark(row, Feedback.DOWN, "👎 Not relevant") to { verdict(activity, row, Feedback.DOWN, onDone) }
        if (company != null) options += "🔕 Mute company “$company”" to { muteCompany(activity, company, onDone) }
        val words = Mutes.titleWords(row.item.title)
        if (words.isNotEmpty()) options += "🔕 Mute words in the title…" to { pickWords(activity, words, onDone) }

        AlertDialog.Builder(activity)
            .setTitle(row.item.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The current verdict carries a tick; choosing it again clears it. */
    private fun mark(row: Row, value: String, label: String) = if (row.feedback == value) "✓ $label (tap to clear)" else label

    /** Sets [value], or clears it when the row already has it. */
    fun verdict(activity: Activity, row: Row, value: String, onDone: (Change) -> Unit) {
        val next = if (row.feedback == value) null else value
        write(activity, {
            it.setFeedback(row.id, next)
            Score.reload(it)
        }) {
            onDone(Change.Verdict(next))
            toast(activity, if (next == null) "Cleared" else "Thanks — the score learns from it")
        }
    }

    private fun muteCompany(activity: Activity, company: String, onDone: (Change) -> Unit) {
        write(activity, { it.mutes.add(Mutes.Kind.COMPANY.db, Mutes.key(company), company) }) {
            onDone(Change.Muted)
            toast(activity, "Muted $company. Undo in Settings → Muted")
        }
    }

    private fun pickWords(activity: Activity, words: List<String>, onDone: (Change) -> Unit) {
        val chosen = BooleanArray(words.size)
        AlertDialog.Builder(activity)
            .setTitle("Never show titles with")
            .setMultiChoiceItems(words.toTypedArray(), chosen) { _, which, on -> chosen[which] = on }
            .setPositiveButton("Mute") { _, _ ->
                val picked = words.filterIndexed { index, _ -> chosen[index] }
                if (picked.isEmpty()) return@setPositiveButton
                write(activity, { store -> picked.forEach { store.mutes.add(Mutes.Kind.WORD.db, it, it) } }) {
                    onDone(Change.Muted)
                    toast(activity, "Muted ${picked.joinToString(", ")}. Undo in Settings → Muted")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Off the main thread; the mutes are re-read so the list and the next run both see them. */
    private fun write(activity: Activity, change: (Store) -> Unit, then: () -> Unit) {
        activity.io {
            val ok = runCatching {
                val store = Store.shared(activity)
                change(store)
                Mutes.load(store)
            }.onFailure { Log.error("Feedback: ${it.javaClass.simpleName}: ${it.message}") }.isSuccess
            activity.onUi { if (ok) then() else toast(activity, "Could not save that") }
        }
    }

    private fun toast(activity: Activity, text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
