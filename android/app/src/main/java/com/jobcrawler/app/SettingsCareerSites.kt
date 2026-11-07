package com.jobcrawler.app

import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.jobcrawler.app.sources.CareerSites

/** The built-in sites, then the ones added here, each removable. */
internal fun SettingsActivity.showCareerSites() {
    careerSiteList.removeAllViews()
    for (site in CareerSites.SITES) careerSiteList.addView(careerSiteRow(site.company, "${hostOf(site.sitemap)} · built in", null))
    for (site in UserSites.load(this)) {
        careerSiteRow(site.company, "${hostOf(site.sitemap)} · ${site.location}") { removeCareerSite(site) }
            .let { careerSiteList.addView(it) }
    }
}

internal fun SettingsActivity.careerSiteRow(name: String, caption: String, onRemove: (() -> Unit)?) = Ui.row(this).apply {
    minimumHeight = dp(56)
    addView(Ui.column(this@careerSiteRow).apply {
        addView(Ui.body(this@careerSiteRow, name, 16f))
        addView(Ui.muted(this@careerSiteRow, caption))
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (onRemove != null) addView(Ui.filterChip(this@careerSiteRow, "Remove") { onRemove() })
}

internal fun SettingsActivity.hostOf(url: String) = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrNull() ?: url

internal fun SettingsActivity.addCareerSite() {
    val field = EditText(this, null, 0, R.style.Field).apply {
        hint = "https://company.com/careers"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    val box = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(field, Ui.matchWidth())
    }
    AlertDialog.Builder(this)
        .setTitle("Add a careers site")
        .setMessage("Paste the company's careers or jobs page. The app finds its sitemap and the pages its jobs live on.")
        .setView(box)
        .setPositiveButton("Find jobs") { _, _ -> findCareerSite(field.text.toString()) }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun SettingsActivity.findCareerSite(link: String) {
    val taken = CareerSites.SITES.map { it.id } + UserSites.load(this).map { it.id }
    toast("Looking for job pages…")
    background({ runCatching { SiteFinder().find(link, taken) } }) { result ->
        result.fold(::confirmCareerSite) { error ->
            AlertDialog.Builder(this)
                .setTitle("No job pages found")
                .setMessage(
                    if (error is SiteFinder.NotFound) error.message
                    else "Could not read the site: ${error.javaClass.simpleName}: ${error.message}",
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }
}

/** Shows what was found, with the name and place editable, before anything is saved. */
internal fun SettingsActivity.confirmCareerSite(found: SiteFinder.Found) {
    val company = EditText(this, null, 0, R.style.Field).apply { setText(found.site.company) }
    val location = EditText(this, null, 0, R.style.Field).apply { setText(found.site.location) }
    val box = Ui.column(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(fieldLabel("Company"))
        addView(company, Ui.matchWidth())
        addView(fieldLabel("Where its jobs are"))
        addView(location, Ui.matchWidth())
    }
    val examples = found.examples.joinToString("\n") { "• " + it.substringAfter("://") }

    AlertDialog.Builder(this)
        .setTitle("${found.jobCount} job pages found")
        .setMessage("$examples\n\nIts first run records these without sending them; after that, each new one is sent.")
        .setView(box)
        .setPositiveButton("Add") { _, _ ->
            val site = found.site.copy(
                company = company.text.toString().trim().ifEmpty { found.site.company },
                location = location.text.toString().trim().ifEmpty { found.site.location },
            )
            UserSites.add(this, site)
            // Adding a site means wanting it crawled.
            sourceSwitches[CareerSites.KEY]?.isChecked = true
            showCareerSites()
            toast("Added ${site.company}")
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun SettingsActivity.removeCareerSite(site: SavedSite) {
    AlertDialog.Builder(this)
        .setTitle("Remove ${site.company}?")
        .setMessage("Its jobs stop being checked. Jobs already found stay in the list.")
        .setPositiveButton("Remove") { _, _ ->
            UserSites.remove(this, site.id)
            showCareerSites()
        }
        .setNegativeButton("Cancel", null)
        .show()
}
