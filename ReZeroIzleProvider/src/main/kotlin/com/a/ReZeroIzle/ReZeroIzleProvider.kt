package com.a.rezeroizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ReZero İzle CloudStream 3 Provider
 *
 * Single-anime Turkish-subtitle site. Each season is exposed as a separate
 * searchable entry with its own poster so the user can jump straight to any season.
 *
 * Season index HTML structure (confirmed from raw page source):
 *   <div class="hub-card">
 *     <ul>
 *       <li><a href="https://rezeroizle.com/sezon/1/bolum/1.html">1. Bölüm …</a></li>
 *       …
 *     </ul>
 *   </div>
 *   (The "İzlemeye Başla" CTA sits in a sibling div.hub-cta, NOT inside the <ul>.)
 *
 * Episode page GDrive link (confirmed from raw page source):
 *   <a id="downloadBtn"
 *      href="https://drive.google.com/uc?export=download&id=FILE_ID">⬇ İndir</a>
 *   → [?&]id= regex catches FILE_ID reliably.
 */
class ReZeroIzleProvider : MainAPI() {

    override var mainUrl        = "https://rezeroizle.com"
    override var name           = "ReZero İzle"
    override var lang           = "tr"
    override val hasMainPage    = false
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    private val baseHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer"         to "$mainUrl/",
    )

    // ── Catalogue ─────────────────────────────────────────────────────────────
    private data class Catalogue(
        val title: String,
        val url: String,
        val poster: String,
        val plot: String,
        val type: TvType,
    )

    private val catalogue = listOf(
        Catalogue(
            title  = "Re:Zero 1. Sezon",
            url    = "$mainUrl/sezon/1/index.html",
            poster = "$mainUrl/images/s1.jpg",
            plot   = "Subaru Natsuki başka bir dünyaya ışınlanır ve 'Ölümden Dönüş' " +
                     "yeteneğiyle hayatta kalmaya çalışır. (1. Sezon – 25 bölüm)",
            type   = TvType.Anime,
        ),
        Catalogue(
            title  = "Re:Zero 2. Sezon",
            url    = "$mainUrl/sezon/2/index.html",
            poster = "$mainUrl/images/s2.webp",
            plot   = "Subaru yeni tehditlerle yüzleşirken Rem gizemli bir uykuya dalar. " +
                     "(2. Sezon – 25 bölüm)",
            type   = TvType.Anime,
        ),
        Catalogue(
            title  = "Re:Zero 3. Sezon",
            url    = "$mainUrl/sezon/3/index.html",
            poster = "$mainUrl/images/s3.webp",
            plot   = "Re:Zero 3. Sezon – Türkçe altyazılı.",
            type   = TvType.Anime,
        ),
        Catalogue(
            title  = "Re:Zero 4. Sezon",
            url    = "$mainUrl/sezon/4/index.html",
            poster = "$mainUrl/images/s4.webp",
            plot   = "Re:Zero 4. Sezon – Türkçe altyazılı.",
            type   = TvType.Anime,
        ),
        Catalogue(
            title  = "Re:Zero – Memory Snow (OVA)",
            url    = "$mainUrl/sezon/1/ozel/memory-snow.html",
            poster = "$mainUrl/images/s1.jpg",
            plot   = "Kış şenliği ve karlı bir gün. Subaru ile Emilia'nın tatlı kış macerası.",
            type   = TvType.OVA,
        ),
        Catalogue(
            title  = "Re:Zero – Frozen Bond (OVA)",
            url    = "$mainUrl/sezon/2/ozel/frozen-bond.html",
            poster = "$mainUrl/images/frozen-bond.webp",
            plot   = "Emilia'nın geçmişi ve Frozen Bond – Türkçe altyazılı OVA.",
            type   = TvType.OVA,
        ),
    )

    // ── Search ─────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val keywords = listOf(
            "re", "zero", "rezero", "subaru", "emilia", "rem", "ram",
            "memory", "snow", "frozen", "bond", "sezon", "ova",
        )
        val isMatch = q.isBlank() || keywords.any { q.contains(it) }
        if (!isMatch) return emptyList()

        return catalogue.map { entry ->
            newAnimeSearchResponse(entry.title, entry.url, entry.type) {
                posterUrl = entry.poster
            }
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val entry  = catalogue.find { it.url == url }
        val title  = entry?.title  ?: "Re:Zero"
        val poster = entry?.poster ?: "$mainUrl/images/s1.jpg"
        val plot   = entry?.plot
        val type   = entry?.type   ?: TvType.Anime

        // OVA pages are the episode themselves — wrap as single episode
        if (url.contains("/ozel/")) {
            val episodes = listOf(
                newEpisode(url) {
                    this.name    = title
                    this.season  = 1
                    this.episode = 1
                }
            )
            return newAnimeLoadResponse(title, url, type) {
                posterUrl  = poster
                this.plot  = plot
                this.tags  = listOf("OVA", "Türkçe Altyazı")
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }

        // Season index pages
        // The live HTML uses relative hrefs (e.g. "bolum/5.html"), NOT absolute ones.
        // Chrome rewrites them to absolute when saving as MHT, which caused confusion.
        // We resolve them manually against the season index page URL.
        val seasonNum = Regex("""/sezon/(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val doc = try {
            app.get(url, headers = baseHeaders).document
        } catch (e: Exception) {
            android.util.Log.e("ReZeroIzle", "Season $seasonNum index fetch failed: ${e.message}")
            return newAnimeLoadResponse(title, url, type) {
                posterUrl = poster
                this.plot = plot
                addEpisodes(DubStatus.Subbed, emptyList())
            }
        }

        val episodes = mutableListOf<Episode>()
        var counter  = 0

        // Base for resolving relative hrefs.
        // The season index is at /sezon/N/index.html, so the base dir is /sezon/N/
        // e.g. a relative href "bolum/5.html" → https://rezeroizle.com/sezon/1/bolum/5.html
        val pageBaseDir = url.substringBeforeLast("/") + "/"

        // Selector confirmed against raw HTML: div.hub-card > ul > li > a[href]
        // Excludes the CTA link (div.hub-cta) and the navbar logo link.
        doc.select("div.hub-card ul li a[href]").forEach { el ->
            val rawHref = el.attr("href").ifBlank { return@forEach }
            val label   = el.text().trim().ifBlank { return@forEach }

            // Resolve the href to an absolute URL.
            // Priority: abs:href (Jsoup resolves against its base URI) →
            //           manual resolution against the season index page dir.
            val href = el.attr("abs:href").ifBlank {
                when {
                    rawHref.startsWith("http") -> rawHref          // already absolute
                    rawHref.startsWith("/")    -> "$mainUrl$rawHref" // root-relative
                    else                       -> pageBaseDir + rawHref // relative to page
                }
            }.ifBlank { return@forEach }

            counter++

            val epNum: Int? = when {
                href.contains("/bolum/")    ->
                    Regex("""/bolum/(\d+)\.html""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                href.contains("/arabolum/") ->
                    Regex("""/arabolum/(\d+)\.html""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                else -> counter
            }

            episodes.add(
                newEpisode(href) {
                    this.name    = label
                    this.season  = 1
                    this.episode = epNum
                }
            )
            android.util.Log.d("ReZeroIzle", "S$seasonNum E$epNum [$href]: $label")
        }

        android.util.Log.d("ReZeroIzle", "Season $seasonNum: ${episodes.size} episodes loaded")

        return newAnimeLoadResponse(title, url, type) {
            posterUrl  = poster
            this.plot  = plot
            this.tags  = listOf("İsekai", "Fantazi", "Aksiyon", "Türkçe Altyazı")
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    // GDrive ID confirmed in raw HTML inside the download button:
    //   <a id="downloadBtn" href="https://drive.google.com/uc?export=download&id=FILE_ID">
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        android.util.Log.d("ReZeroIzle", "loadLinks url=$data")

        val html = try {
            app.get(data, headers = baseHeaders).text
        } catch (e: Exception) {
            android.util.Log.e("ReZeroIzle", "Episode fetch error: ${e.message}")
            return false
        }
        android.util.Log.d("ReZeroIzle", "Episode page len=${html.length}")

        // Detect unreleased episodes: the site renders unreleasedOverlay with display:none
        // for released episodes, and makes it visible (no display:none) for unreleased ones.
        val isUnreleased = html.contains("unreleasedOverlay") &&
            !Regex("""unreleasedOverlay[^>]*display\s*:\s*none""").containsMatchIn(html)
        if (isUnreleased) {
            android.util.Log.w("ReZeroIzle", "Episode not yet translated by site owner. url=$data")
            return false
        }

        val fileId =
            Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,})""")
                .find(html)?.groupValues?.get(1)
            // &amp; is the HTML-encoded form of & inside href attributes
            ?: Regex("""[?&](?:amp;)?id=([A-Za-z0-9_-]{25,})""")
                .find(html)?.groupValues?.get(1)
            ?: Regex("""docs\.google\.com/[^"'\s]*[?&/]d/([A-Za-z0-9_-]{25,})""")
                .find(html)?.groupValues?.get(1)

        if (fileId == null) {
            android.util.Log.w("ReZeroIzle",
                "No GDrive ID found — episode may not be translated yet. " +
                "Snippet: ${html.take(300)}")
            return false
        }
        android.util.Log.d("ReZeroIzle", "GDrive fileId=$fileId")

        // confirm=t bypasses the large-file download interstitial
        val directUrl =
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
        callback(
            newExtractorLink(
                source = name,
                name   = "Google Drive",
                url    = directUrl,
                type   = ExtractorLinkType.VIDEO,
            ) {
                quality = Qualities.Unknown.value
                referer = "https://drive.google.com/"
            }
        )
        return true
    }
}
