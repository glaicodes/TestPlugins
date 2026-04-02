package com.a.rezeroizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ReZero İzle CloudStream 3 Provider
 *
 * Single-anime site (Re:Zero) with Turkish subtitles, hosted on Google Drive.
 *
 * Site structure:
 *   Home:          https://rezeroizle.com/index.html
 *   Season index:  https://rezeroizle.com/sezon/{n}/index.html
 *   Regular ep:    https://rezeroizle.com/sezon/{s}/bolum/{n}.html
 *   Interlude ep:  https://rezeroizle.com/sezon/{s}/arabolum/{n}.html
 *   Special / OVA: https://rezeroizle.com/sezon/{s}/ozel/{name}.html
 *
 * Video links:
 *   GDrive file ID is embedded in the episode page HTML (inline script or iframe).
 *   We extract the ID with regexes and build a direct download URL.
 */
class ReZeroIzleProvider : MainAPI() {

    override var mainUrl         = "https://rezeroizle.com"
    override var name            = "ReZero İzle"
    override var lang            = "tr"
    override val hasMainPage      = true
    override val supportedTypes   = setOf(TvType.Anime, TvType.OVA)

    private val animeTitle = "Re:Zero kara Hajimeru Isekai Seikatsu"
    private val animeDesc  =
        "Lise öğrencisi Subaru Natsuki, aniden başka bir dünyaya ışınlanır. " +
        "Karşılaştığı tehlikeleri 'Ölümden Dönüş' yeteneğiyle atlatmaya çalışır."
    private val animePoster = "$mainUrl/images/icon.png"
    private val animeUrl    = "$mainUrl/index.html"

    private val baseHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer"         to "$mainUrl/",
    )

    // ── Home page ──────────────────────────────────────────────────────────────
    // One category per available season + the OVAs, so the user lands on a
    // useful episode list without having to enter load() first.
    override val mainPage = mainPageOf(
        "$mainUrl/sezon/1/index.html" to "1. Sezon",
        "$mainUrl/sezon/2/index.html" to "2. Sezon",
        "$mainUrl/sezon/3/index.html" to "3. Sezon",
        "$mainUrl/sezon/4/index.html" to "4. Sezon",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Home page shows the anime as a single card — no pagination needed.
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val result = newAnimeSearchResponse(animeTitle, animeUrl, TvType.Anime) {
            posterUrl = animePoster
        }
        return newHomePageResponse(request.name, listOf(result))
    }

    // ── Search ─────────────────────────────────────────────────────────────────
    // This site carries only Re:Zero. We do a fuzzy match and always return it
    // when the query is at all related; blank queries also return it.
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        val keywords = listOf("re", "zero", "rezero", "sıfır", "subaru", "emilia", "rem", "ram")
        val matches = q.isBlank() || keywords.any { q.contains(it) }
        if (!matches) return emptyList()
        return listOf(
            newAnimeSearchResponse(animeTitle, animeUrl, TvType.Anime) {
                posterUrl = animePoster
            }
        )
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    // Parses all available seasons from their index pages and builds a flat
    // episode list with correct season/episode numbering.
    override suspend fun load(url: String): LoadResponse {
        val episodes = mutableListOf<Episode>()

        for (season in 1..4) {
            val seasonUrl = "$mainUrl/sezon/$season/index.html"
            try {
                val doc = app.get(seasonUrl, headers = baseHeaders).document
                // All episode links live inside the <ul> list on the season index page.
                // Each <li> has exactly one <a href="...">title</a>.
                // The "İzlemeye Başla" call-to-action link at the bottom is outside the <ul>.
                var epCounter = 0
                doc.select("ul li a[href]").forEach { el ->
                    val href  = fixUrl(el.attr("abs:href").ifBlank { el.attr("href") })
                        .ifBlank { return@forEach }
                    val title = el.text().trim().ifBlank { return@forEach }

                    // Only include episode / interlude / special pages from this site
                    if (!href.startsWith(mainUrl)) return@forEach
                    if (!href.contains("/sezon/")) return@forEach

                    epCounter++

                    // Derive a display episode number from the URL path when possible,
                    // so regular episodes keep their "true" number (e.g. S1E12 = 12).
                    // Interlude and OVA episodes get sequential numbers within their season.
                    val epNum: Int? = when {
                        href.contains("/bolum/") ->
                            Regex("""/bolum/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        href.contains("/arabolum/") ->
                            Regex("""/arabolum/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        else -> epCounter   // OVA / named specials
                    }

                    episodes.add(
                        newEpisode(href) {
                            this.name    = title
                            this.season  = season
                            this.episode = epNum
                        }
                    )
                }
                android.util.Log.d("ReZeroIzle", "Season $season: $epCounter episodes parsed")
            } catch (e: Exception) {
                // Season 4 may not be live yet — silently skip missing seasons.
                android.util.Log.w("ReZeroIzle", "Season $season skipped: ${e.message}")
            }
        }

        return newAnimeLoadResponse(animeTitle, url, TvType.Anime) {
            posterUrl  = animePoster
            this.plot  = animeDesc
            this.tags  = listOf("İsekai", "Fantazi", "Aksiyon", "Türkçe Altyazı")
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    // The episode page embeds a Google Drive file ID inside an inline <script>
    // or an <iframe src="...drive.google.com..."> element.
    // We try several regex patterns in order of specificity and build a direct
    // download URL from whichever ID we find.
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

        // ── GDrive file ID extraction ──────────────────────────────────────────
        // Priority 1: explicit /file/d/{ID} path in any context (iframe, script, href)
        val fileId =
            Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,})""")
                .find(html)?.groupValues?.get(1)

            // Priority 2: ?id=... or &id=... query parameter (uc?export&id=..., usercontent, etc.)
            ?: Regex("""[?&]id=([A-Za-z0-9_-]{25,})""")
                .find(html)?.groupValues?.get(1)

            // Priority 3: docs.google.com variant with /d/{ID}
            ?: Regex("""docs\.google\.com/[^"'\s]*[?&/]d/([A-Za-z0-9_-]{25,})""")
                .find(html)?.groupValues?.get(1)

            // Priority 4: JS string literal that looks like a GDrive file ID.
            //             GDrive file IDs start with "1" and are 28-33 chars of base64url.
            //             This is a heuristic — only used as a last resort.
            ?: Regex("""["'`](1[A-Za-z0-9_-]{27,32})["'`]""")
                .find(html)?.groupValues?.get(1)

        if (fileId == null) {
            android.util.Log.w(
                "ReZeroIzle",
                "No GDrive file ID found — episode may not be translated yet. " +
                "HTML snippet: ${html.take(400)}"
            )
            return false
        }
        android.util.Log.d("ReZeroIzle", "GDrive fileId=$fileId")

        // Direct download URL.
        // - drive.usercontent.google.com is Google's canonical download endpoint.
        // - confirm=t skips the "large file" interstitial for files > ~100 MB.
        // - export=download forces a download response (Content-Disposition: attachment).
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

        // Also offer a streaming-friendly preview URL as a second source.
        // Some CloudStream players handle this better than the raw download endpoint.
        val previewUrl = "https://drive.google.com/file/d/$fileId/preview"
        callback(
            newExtractorLink(
                source = name,
                name   = "Google Drive (Önizleme)",
                url    = previewUrl,
                type   = ExtractorLinkType.VIDEO,
            ) {
                quality = Qualities.Unknown.value
                referer = "https://drive.google.com/"
            }
        )

        return true
    }
}
