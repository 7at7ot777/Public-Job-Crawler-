package com.jobcrawler.app

import com.jobcrawler.app.sources.AiData
import com.jobcrawler.app.sources.Workable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDataTest {
    private fun job(
        title: String,
        excerpt: String? = "Freelance work.",
        location: String? = "Spain",
        market: String? = null,
        remote: Boolean = false,
        source: String = Workable.KEY,
    ) = Item(
        source = source,
        externalId = title,
        title = title,
        url = "https://jobs.workable.com/view/1",
        company = "Vendor",
        location = location,
        market = market,
        excerpt = excerpt,
        remote = remote,
    )

    @Test
    fun `names the data a job works on`() {
        assertEquals("Arabic · Audio", AiData.modalitiesOf(job("Arabic Speech Transcriber", "MSA and Egyptian dialect")))
        assertEquals("Image", AiData.modalitiesOf(job("Image Annotation Specialist", null)))
        assertNull(AiData.modalitiesOf(job("AI Trainer", "Help us.")))
    }

    @Test
    fun `keeps jobs in Egypt or remote, and drops on-site ones abroad`() {
        assertEquals(AiData.KEY, AiData.keep(job("Data Annotator", market = "Egypt"))?.source)
        assertEquals("Arabic", AiData.keep(job("Arabic Annotator", remote = true))?.keyword)
        assertEquals("AI data", AiData.keep(job("Data Annotator", location = "Cairo, Egypt"))?.keyword)
        assertNull(AiData.keep(job("Data Annotator", location = "Manila, Philippines")))
    }

    @Test
    fun `the filter lets labelling roles through and nothing else`() {
        fun verdict(title: String, location: String? = "Remote") =
            Filters.evaluate(job(title, location = location, remote = true, source = AiData.KEY))

        assertTrue(verdict("Arabic Data Annotator").pass)
        assertTrue(verdict("Audio Transcriptionist (Egyptian Arabic)").pass)
        assertTrue(verdict("Arabic Language Expert").pass)
        assertTrue(verdict("AI Rater - Arabic").pass)
        assertEquals("title", verdict("Account Executive").reason)
        assertEquals("location-blocked", verdict("Data Annotator", location = "Bangalore, India").reason)
        assertEquals("seniority-excluded", verdict("Annotation Team Lead").reason)
    }

    @Test
    fun `only generalist AI work, in Arabic or Arabic with English`() {
        fun verdict(title: String, excerpt: String = "Freelance work.") =
            Filters.evaluate(job(title, excerpt = excerpt, location = "Remote", remote = true, source = AiData.KEY))

        assertTrue(verdict("AI Trainer").pass)
        assertTrue(verdict("Arabic AI Trainer").pass)
        assertTrue(verdict("AI Trainer (Arabic & English)").pass)
        assertTrue(verdict("مدرب ذكاء اصطناعي", "العمل باللغة العربية").pass)
        assertTrue(verdict("Data Annotator", "Fluent in Arabic and English.").pass)
        assertTrue(verdict("AI Trainer", "French, German and Arabic speakers welcome.").pass)
        assertTrue(verdict("AI Rater", "Our office is in Germany.").pass)

        // Software gigs and image labelling in no particular field: kept.
        assertTrue(verdict("AI Trainer - Software Engineering").pass)
        assertTrue(verdict("Python Coding AI Trainer").pass)
        assertTrue(verdict("Image Labeler").pass)
        assertTrue(verdict("Image Annotation Engineer").pass)
        assertTrue(verdict("Video Labeling Specialist").pass)
        assertTrue(verdict("وسم الصور - عمل حر").pass)

        assertEquals("title-excluded", verdict("Medical Image Annotator").reason)
        assertEquals("title-excluded", verdict("Mechanical Engineering AI Trainer").reason)
        assertEquals("title-excluded", verdict("Freelance Mechanical CAD&Simulation Engineer - AI Trainer").reason)
        assertEquals("title-excluded", verdict("CAD Designer - AI Trainer").reason)
        assertNotEquals("title-excluded", verdict("Academy AI Trainer").reason)
        assertEquals("title-excluded", verdict("Physics Expert AI Trainer").reason)
        assertEquals("title-excluded", verdict("Pharmacist AI Rater").reason)
        assertEquals("title-excluded", verdict("Medical Transcriptionist").reason)
        assertEquals("title-excluded", verdict("مدرب ذكاء اصطناعي - مهندس مدني").reason)

        assertEquals("language", verdict("French AI Trainer").reason)
        assertEquals("language", verdict("AI Rater", "Native German speaker required.").reason)
    }

    private fun variant(id: String, title: String, company: String? = "Mindrift", location: String, market: String? = null, remote: Boolean = false) =
        Item(
            source = AiData.KEY,
            externalId = id,
            title = title,
            url = "https://jobs.workable.com/view/$id",
            company = company,
            location = location,
            market = market,
            remote = remote,
        )

    private fun collapsed(vararg items: Item): List<Item> {
        val seen = LinkedHashMap<String, Item>()
        for (item in items) AiData.collect(seen, item)
        return seen.values.toList()
    }

    @Test
    fun `one role posted once per country is one item in a run`() {
        val title = "Freelance CAD Engineer - AI Trainer"
        val items = collapsed(
            variant("es", title, location = "Madrid, Spain", remote = true),
            variant("ma", "Ultrasound Image Annotator", company = "CloudFactory", location = "Rabat, Morocco", remote = true),
            variant("pl", title, location = "Warsaw, Poland", remote = true),
            // Case and punctuation differ, as they do between requisitions.
            variant("br", "freelance cad engineer – ai trainer", company = "MINDRIFT", location = "Brazil", remote = true),
            variant("ke", "Ultrasound Image Annotator", company = "CloudFactory", location = "Nairobi, Kenya", remote = true),
        )

        assertEquals(listOf("es", "ma"), items.map { it.externalId })
    }

    @Test
    fun `the variant kept is the one the filter is likeliest to want`() {
        val title = "Arabic AI Trainer"
        // First seen in a blocked country, then remote elsewhere, then placed in Egypt.
        val items = collapsed(
            variant("in", title, location = "Bangalore, India", remote = true),
            variant("es", title, location = "Spain", remote = true),
            variant("eg", title, location = "Cairo, Egypt", market = "Egypt"),
            variant("de", title, location = "Germany", remote = true),
        )

        assertEquals(1, items.size)
        assertEquals("eg", items.single().externalId)
        assertTrue(Filters.evaluate(items.single()).pass)

        // A blocked first sighting loses to a remote one, and order is kept.
        assertEquals(listOf("es"), collapsed(variant("in", title, location = "India", remote = true), variant("es", title, location = "Spain", remote = true)).map { it.externalId })
    }

    @Test
    fun `different roles, and roles with no company, are not collapsed`() {
        val items = collapsed(
            variant("a", "AI Trainer", location = "Spain", remote = true),
            variant("b", "AI Trainer", company = "Other Vendor", location = "Spain", remote = true),
            variant("c", "Arabic AI Trainer", location = "Spain", remote = true),
            variant("d", "AI Trainer", company = null, location = "Spain", remote = true),
            variant("e", "AI Trainer", company = null, location = "Peru", remote = true),
        )

        assertEquals(listOf("a", "b", "c", "d", "e"), items.map { it.externalId })
    }

    @Test
    fun `the run key is the store fingerprint, so a run and the store agree`() {
        val item = variant("x", "Data Annotator", location = "Spain")
        assertEquals(Store.fingerprintOf("Mindrift", "Data Annotator"), AiData.dedupKey(item))
    }

    @Test
    fun `parsed Workable requisitions per country fold before filtering`() {
        fun requisition(id: String, country: String) = """
            {"id":"$id","state":"published","title":"Freelance Arabic AI Trainer","url":"https://jobs.workable.com/view/$id",
             "company":{"title":"Mindrift"},"workplace":"remote","locations":["$country"],"location":{"countryName":"$country"}}
        """.trimIndent()
        val body = """{"jobs":[${listOf("Spain", "Poland", "Brazil", "Mexico", "Egypt").mapIndexed { i, c -> requisition("r$i", c) }.joinToString(",")}]}"""

        val seen = LinkedHashMap<String, Item>()
        for (location in AiData.LOCATIONS) {
            val (items, _) = Workable.parse(body, "AI Trainer", location)
            for (job in items) AiData.keep(job)?.let { AiData.collect(seen, it) }
        }

        assertEquals(1, seen.size)
        // The Egypt requisition, placed there by the Egypt search, wins.
        assertEquals("r4", seen.values.single().externalId)
        assertEquals("Egypt", seen.values.single().market)
    }
}
