package com.jobcrawler.app

import com.jobcrawler.app.sources.Freelance
import com.jobcrawler.app.sources.Khamsat
import com.jobcrawler.app.sources.Mostaql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mostaql and Khamsat, the Arabic freelance boards: parsed offline from trimmed real pages. */
class FreelanceTest {

    // A Mostaql listing row as the programming category ships it (2026-11-06), trimmed.
    private fun mostaqlRow(id: String, title: String, brief: String, offers: Int = 13) = """
        <tr class="project-row">
        <td class="row-td">
            <div class="card-title_wrapper">
                <div class="card--title">
                    <h2 class="mrg--bt-reset">
                        <a href="https://mostaql.com/project/$id-%D8%A8%D9%86%D8%A7%D8%A1" class="">$title</a>
                    </h2>
                    <ul class="project__meta list-meta-items">
                        <li class="text-muted"><i class="fa fa-user"></i><bdi>Mohamed F.</bdi></li>
                        <li class="text-muted">
                            <time datetime="2026-11-06 18:20:43" title="2026-11-06 18:20:43" itemprop="datePublished">
                            <i class="fa fa-clock-o"></i> منذ 59 دقيقة</time>
                        </li>
                        <li class="text-muted"><span class="hsoub-file-signature-icon"></span> $offers عرض</li>
                    </ul>
                </div>
            </div>
            <div class="mrg--tt">
                <p class="text-wrapper-div project__brief "><a href="https://mostaql.com/project/$id" class="details-url">$brief</a></p>
            </div>
        </td>
        </tr>
    """

    private fun mostaqlList(vararg rows: String) =
        "<html><body><table class=\"table projects-table\"><tbody data-filter=\"collection\">" + rows.joinToString("") +
            "</tbody></table></body></html>"

    // The meta panel and brief of a Mostaql project page, trimmed.
    private fun mostaqlDetail(status: String = "مفتوح") = """
        <html><body>
        <div class="carda__body collapse in" id="project-meta-panel">
          <div class="meta-container text-meta"><div class="meta-rows">
            <div class="meta-row"><div class="meta-label">حالة المشروع</div>
              <div class="meta-value"><bdi class="label label-prj-open">$status</bdi></div></div>
            <div class="meta-row"><div class="meta-label">تاريخ النشر</div>
              <div class="meta-value"><time datetime="2026-11-06 18:20:43">منذ ساعة</time></div></div>
            <div class="meta-row"><div class="meta-label">الميزانية</div>
              <div class="meta-value" data-type="project-budget_range"><span dir="rtl">${'$'}50.00 - ${'$'}100.00</span></div></div>
            <div class="meta-row"><div class="meta-label">مدة التنفيذ</div>
              <div class="meta-value">4
            أيام</div></div>
            <div class="meta-row"><div class="meta-label">المهارات</div><div class="meta-value"></div></div>
            <div class="meta-row pdn--tn-imp"><div class=""><ul class="skills text-zeta list-tags">
              <li class="skills__item"><a href="https://mostaql.com/projects/skill/laravel" class="tag"><i class="fa fa-tag"></i> <bdi>Laravel</bdi></a></li>
              <li class="skills__item"><a href="https://mostaql.com/projects/skill/api" class="tag"><i class="fa fa-tag"></i> <bdi>برمجة مواقع</bdi></a></li>
            </ul></div></div>
          </div></div>
        </div>
        <div id="projectDetailsTab"><div class="pdn--am pdn--sm-hn">
          <div class="text-wrapper-div carda__content"><p>أريد لوحة تحكم بلارافيل مع REST API لتطبيق جوال.</p><p>التسليم خلال أسبوع.</p></div>
        </div></div>
        </body></html>
    """

    // A Khamsat request row (2026-11-06), as the listing ships it.
    private fun khamsatRow(id: String, title: String) = """
        <tr id="forum_post-$id" class="forum_post">
          <td class="avatar-td text-center"><a href="/user/nesma_ahmed2"><img class="u-circle" src="https://avatars.hsoubcdn.com/x?s=128" /></a></td>
          <td class="details-td">
            <h3 class="details-head"><a class="ajaxbtn" href="/community/requests/$id-%D8%A8%D8%A7%D9%88%D8%B1">$title</a></h3>
            <ul class="details-list">
               <li><a class="user" href="/user/nesma_ahmed2"><i class="fa fa-fw fa-user"></i> .Nesma R</a></li>
               <li class="d-lg-inline-block d-none"><i class="fa-regular fa-fw fa-clock"></i>
                  <span dir="ltr" title="05&#x2F;11&#x2F;2026 17:26:45 GMT">منذ يوم وساعة</span></li>
               <li class="d-lg-none d-inline-block"><span title="06&#x2F;11&#x2F;2026 19:15:15 GMT"><i class="fa-regular fa-fw fa-clock"></i> آخر تفاعل منذ 4 دقائق</span></li>
            </ul>
          </td>
        </tr>
    """

    private fun gig(source: String, title: String, excerpt: String? = null, location: String? = null) = Item(
        source = source,
        externalId = "1",
        title = title,
        url = "https://example.com/1",
        market = Freelance.market(if (source == Mostaql.KEY) "Mostaql" else "Khamsat"),
        location = location,
        excerpt = excerpt,
        remote = true,
    )

    @Test
    fun `the stack is found in English and in Arabic`() {
        assertTrue(Freelance.fits("Laravel API for a mobile app"))
        assertTrue(Freelance.fits("مطلوب مطور PHP لموقع"))
        assertTrue(Freelance.fits("مطلوب مبرمج لارافيل لبناء لوحة تحكم"))
        assertTrue(Freelance.fits("تطوير باك اند لتطبيق توصيل"))
        assertTrue(Freelance.fits("ربط الموقع مع واجهة برمجة الدفع"))
        assertTrue(Freelance.fits("مطور برمجيات Full Stack لإصلاح نظام"))
        assertTrue(Freelance.fits("مطلوب مطور فول ستاك"))
        assertEquals(listOf("Laravel", "Backend"), Freelance.stack("Laravel REST API"))
    }

    @Test
    fun `Arabic words that only look like the stack do not count`() {
        // "نود أن" is "we would like"; "جودة" is "quality". Neither is Node.js or Go.
        assertFalse(Freelance.fits("نود أن نصمم شعار لشركتنا"))
        assertFalse(Freelance.fits("تصميم بجودة عالية لمتجر"))
        assertFalse(Freelance.fits("Shopify store design"))
    }

    @Test
    fun `a budget range is compacted`() {
        assertEquals("$50–100", Freelance.budget("$50.00 - $100.00"))
        assertEquals("$1500–3000", Freelance.budget("$1,500.00 - $3,000.00"))
        assertEquals("$250", Freelance.budget("$250.00"))
        assertNull(Freelance.budget("غير محدد"))
        assertNull(Freelance.budget(null))
    }

    @Test
    fun `a Mostaql listing row is a gig with its offers and brief`() {
        val items = Mostaql.parseList(
            mostaqlList(
                mostaqlRow("1291250", "مطلوب مبرمج لارافيل", "لوحة تحكم مع API"),
                mostaqlRow("1290869", "تصميم شعار", "شعار احترافي لمتجر", offers = 4),
            ),
        )
        assertEquals(listOf("1291250", "1290869"), items.map { it.externalId })

        val first = items.first()
        assertEquals("mostaql", first.source)
        assertEquals("مطلوب مبرمج لارافيل", first.title)
        assertTrue(first.url, first.url.startsWith("https://mostaql.com/project/1291250-"))
        assertEquals("Mohamed F.", first.company)
        assertEquals("2026-11-06T18:20:43Z", first.postedAt)
        assertEquals("💼 Freelance · Mostaql", first.market)
        assertTrue(first.remote)
        assertTrue(first.excerpt!!.startsWith("📨 13 offers so far"))
        assertTrue(first.excerpt!!.contains("لوحة تحكم مع API"))
        assertEquals("Laravel, Backend", first.keyword)
        // The second names no stack: listed, and left for Filters to drop.
        assertNull(items[1].keyword)
    }

    @Test
    fun `a page with no project rows is no projects`() {
        assertTrue(Mostaql.parseList("<html><body>تعذر التحميل</body></html>").isEmpty())
        assertNull(Mostaql.parseDetail(""))
        assertNull(Mostaql.parseDetail("<html><body>no meta</body></html>"))
    }

    @Test
    fun `a Mostaql project page gives the budget, duration, skills and brief`() {
        val detail = Mostaql.parseDetail(mostaqlDetail())
        assertNotNull(detail)
        detail!!
        assertTrue(detail.open)
        assertEquals("$50–100", detail.budget)
        assertEquals("4 أيام", detail.duration)
        assertEquals(listOf("Laravel", "برمجة مواقع"), detail.skills)
        assertTrue(detail.description!!.contains("REST API"))

        assertFalse(Mostaql.parseDetail(mostaqlDetail(status = "مغلق"))!!.open)
    }

    @Test
    fun `a project page's facts land on its card`() {
        val card = Mostaql.parseList(mostaqlList(mostaqlRow("1291250", "لوحة تحكم لتطبيق", "تفاصيل في الصفحة"))).single()
        val full = Mostaql.apply(card, Mostaql.parseDetail(mostaqlDetail())!!)
        assertEquals("$50–100", full.salary)
        val lines = full.excerpt!!.lines()
        assertEquals("⏱ Delivery: 4 أيام", lines[0])
        assertEquals("📨 13 offers so far", lines[1])
        assertEquals("🏷 Laravel · برمجة مواقع", lines[2])
        assertTrue(full.excerpt!!.contains("REST API"))
        // The brief named no stack; the page's skills and brief do.
        assertEquals("Laravel, Backend", full.keyword)
    }

    @Test
    fun `a Khamsat request row has its title and the time it was posted`() {
        val items = Khamsat.parseList("<table>" + khamsatRow("800217", "باور بوينت") + khamsatRow("800260", "مبرمج لارافيل لموقع") + "</table>")
        assertEquals(listOf("800217", "800260"), items.map { it.externalId })

        val first = items.first()
        assertEquals("khamsat", first.source)
        assertEquals("باور بوينت", first.title)
        assertEquals("https://khamsat.com/community/requests/800217-%D8%A8%D8%A7%D9%88%D8%B1", first.url)
        assertEquals("Nesma R", first.company)
        // The posting time, not the "last reply" one after it.
        assertEquals("2026-11-05T17:26:45Z", first.postedAt)
        assertEquals("💼 Freelance · Khamsat", first.market)
        assertNull(first.keyword)
        assertEquals("Laravel", items[1].keyword)
    }

    @Test
    fun `Khamsat's GMT time reads day first`() {
        assertEquals("2026-11-05T17:26:45Z", Khamsat.posted("05/11/2026 17:26:45 GMT"))
        assertNull(Khamsat.posted("منذ يوم"))
        assertNull(Khamsat.posted(null))
    }

    @Test
    fun `a gig that names the stack passes, one that does not is dropped`() {
        assertTrue(Filters.evaluate(gig(Mostaql.KEY, "مطلوب مبرمج لارافيل", "لوحة تحكم وواجهة برمجة")).pass)
        assertTrue(Filters.evaluate(gig(Khamsat.KEY, "مبرمج Node.js لربط بوت")).pass)
        assertEquals("stack", Filters.evaluate(gig(Mostaql.KEY, "تصميم شعار", "شعار احترافي")).reason)
        assertEquals("stack", Filters.evaluate(gig(Khamsat.KEY, "باور بوينت")).reason)
    }

    @Test
    fun `a gig is freelance wherever its client is`() {
        val riyadh = gig(Mostaql.KEY, "مطلوب مبرمج لارافيل", location = "Riyadh, Saudi Arabia")
        assertEquals(Region.FREELANCE, Region.of(riyadh))
        assertEquals(Region.FREELANCE, Region.home(Khamsat.KEY))
        assertTrue(Region.FREELANCE in Region.SHOWN)
        // Gigs are posted, never auto-emailed.
        assertFalse(Mailer.isAbroad(riyadh))
        assertTrue(Formatter.format(riyadh).contains("#Freelance"))
    }
}
