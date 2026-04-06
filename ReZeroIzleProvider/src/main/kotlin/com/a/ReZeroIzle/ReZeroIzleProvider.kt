package com.a.rezeroizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * ReZero İzle CloudStream 3 Provider
 *
 * Season index HTML: <div class="hub-card"><ul><li><a href="...">
 * Episode GDrive:    JS array of file IDs indexed by window.EPISODE_INDEX
 */
class ReZeroIzleProvider : MainAPI() {

    override var mainUrl        = "https://rezeroizle.com"
    override var name           = "ReZero İzle"
    override var lang           = "tr"
    override val hasMainPage    = false
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    // ── Pre-compiled regex (avoids re-creation per call) ──────────────────────
    companion object {
        private val SEASON_NUM_RE    = Regex("""/sezon/(\d+)/""")
        private val EPISODE_INDEX_RE = Regex("""window\.EPISODE_INDEX\s*=\s*(\d+)""")
        private val GDRIVE_ID_RE    = Regex("""["'`]([A-Za-z0-9_-]{25,45})["'`]""")
        private val GDRIVE_URL_RE   = Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,45})""")
        private val GDRIVE_PARAM_RE = Regex("""[?&](?:amp;)?id=([A-Za-z0-9_-]{25,45})""")

        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )

        private const val PAGE_CACHE_TTL_MS = 300_000L  // 5 minutes
    }

    // ── Stealth headers with rotating UA ──────────────────────────────────────
    private fun stealthHeaders(): Map<String, String> {
        val ua = USER_AGENTS.random()
        return mapOf(
            "User-Agent"       to ua,
            "Accept"           to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language"  to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.5",
            "Accept-Encoding"  to "gzip, deflate, br",
            "Referer"          to "$mainUrl/",
            "Sec-Fetch-Dest"   to "document",
            "Sec-Fetch-Mode"   to "navigate",
            "Sec-Fetch-Site"   to "same-origin",
            "Sec-Fetch-User"   to "?1",
            "Upgrade-Insecure-Requests" to "1",
            "DNT"              to "1",
        )
    }

    private fun scriptHeaders(): Map<String, String> {
        val ua = USER_AGENTS.random()
        return mapOf(
            "User-Agent"       to ua,
            "Accept"           to "*/*",
            "Accept-Language"  to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.5",
            "Referer"          to "$mainUrl/",
            "Sec-Fetch-Dest"   to "script",
            "Sec-Fetch-Mode"   to "no-cors",
            "Sec-Fetch-Site"   to "same-origin",
        )
    }

    // ── Simple in-memory page cache ───────────────────────────────────────────
    private val pageCache = mutableMapOf<String, Pair<Long, org.jsoup.nodes.Document>>()

    private suspend fun fetchDocument(url: String): org.jsoup.nodes.Document {
        val now = System.currentTimeMillis()
        pageCache[url]?.let { (ts, doc) ->
            if (now - ts < PAGE_CACHE_TTL_MS) return doc
        }
        val doc = app.get(url, headers = stealthHeaders()).document
        pageCache[url] = now to doc
        return doc
    }

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
            plot   = "Subaru Natsuki başka bir dünyaya ışınlanır ve 'Ölümden Dönüş' yeteneğiyle hayatta kalmaya çalışır. (1. Sezon – 25 bölüm)",
            type   = TvType.Anime,
        ),
        Catalogue(
            title  = "Re:Zero 2. Sezon",
            url    = "$mainUrl/sezon/2/index.html",
            poster = "$mainUrl/images/s2.webp",
            plot   = "Subaru yeni tehditlerle yüzleşirken Rem gizemli bir uykuya dalar. (2. Sezon – 25 bölüm)",
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
            return newAnimeLoadResponse(title, url, type) {
                posterUrl = poster
                this.plot = plot
                this.tags = listOf("OVA", "Türkçe Altyazı")
                addEpisodes(DubStatus.Subbed, listOf(
                    newEpisode(url) { this.name = title; this.season = 1; this.episode = 1 }
                ))
            }
        }

        val seasonNum = SEASON_NUM_RE.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val doc = try {
            fetchDocument(url)
        } catch (e: Exception) {
            android.util.Log.e("ReZeroIzle", "Season $seasonNum fetch failed: ${e.message}")
            return newAnimeLoadResponse(title, url, type) {
                posterUrl = poster; this.plot = plot
                addEpisodes(DubStatus.Subbed, emptyList())
            }
        }

        val episodes = mutableListOf<Episode>()
        var counter = 0
        val pageBaseDir = url.substringBeforeLast("/") + "/"

        doc.select("div.hub-card ul li a[href]").forEach { el ->
            val rawHref = el.attr("href").ifBlank { return@forEach }
            val label   = el.text().trim().ifBlank { return@forEach }

            val href = el.attr("abs:href").ifBlank {
                when {
                    rawHref.startsWith("http") -> rawHref
                    rawHref.startsWith("/")    -> "$mainUrl$rawHref"
                    else                       -> pageBaseDir + rawHref
                }
            }.ifBlank { return@forEach }

            counter++
            episodes.add(
                newEpisode(href) {
                    this.name    = label
                    this.season  = 1
                    this.episode = counter
                }
            )
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
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        android.util.Log.d("ReZeroIzle", "loadLinks url=$data")

        val html = try {
            app.get(data, headers = stealthHeaders()).text
        } catch (e: Exception) {
            android.util.Log.e("ReZeroIzle", "Episode fetch error: ${e.message}")
            return false
        }

        // ── Step 1: parse page, extract EPISODE_INDEX and external script URLs ──
        val doc2 = org.jsoup.Jsoup.parse(html)

        var episodeIndex = -1
        doc2.select("script:not([src])").forEach { el ->
            val m = EPISODE_INDEX_RE.find(el.html())
            if (m != null) episodeIndex = m.groupValues[1].toInt()
        }
        android.util.Log.d("ReZeroIzle", "EPISODE_INDEX=$episodeIndex")

        val extScripts = doc2.select("script[src]")
            .map { it.attr("abs:src").ifBlank { it.attr("src") } }
            .filter { it.isNotBlank() }

        // ── Step 2: fetch external scripts in parallel, search for GDrive IDs ──
        var fileId: String? = null

        val scriptTexts = coroutineScope {
            extScripts.map { scriptUrl ->
                async {
                    val absUrl = when {
                        scriptUrl.startsWith("http") -> scriptUrl
                        scriptUrl.startsWith("/")    -> "$mainUrl$scriptUrl"
                        else                         -> "$mainUrl/$scriptUrl"
                    }
                    try {
                        delay((50L..200L).random())  // small jitter
                        app.get(absUrl, headers = scriptHeaders()).text
                    } catch (e: Exception) {
                        android.util.Log.w("ReZeroIzle", "Script fetch failed: $absUrl ${e.message}")
                        null
                    }
                }
            }.map { it.await() }
        }

        for (jsText in scriptTexts) {
            if (jsText == null) continue

            val ids = GDRIVE_ID_RE.findAll(jsText)
                .map { it.groupValues[1] }
                .filter { it.matches(Regex("[A-Za-z0-9_-]{25,45}")) }
                .toList()

            if (ids.isNotEmpty()) {
                fileId = if (episodeIndex in ids.indices) {
                    android.util.Log.d("ReZeroIzle", "Using index $episodeIndex → ${ids[episodeIndex]}")
                    ids[episodeIndex]
                } else {
                    android.util.Log.d("ReZeroIzle", "Index $episodeIndex out of range (${ids.size}), using first")
                    ids[0]
                }
                break
            }
        }

        // ── Step 3: fallback — search inline HTML for any GDrive pattern ──
        if (fileId == null) {
            fileId = GDRIVE_URL_RE.find(html)?.groupValues?.get(1)
                ?: GDRIVE_PARAM_RE.find(html)?.groupValues?.get(1)
        }

        if (fileId == null) {
            android.util.Log.w("ReZeroIzle", "No GDrive ID found. episodeIndex=$episodeIndex scripts=${extScripts.size}")
            return false
        }
        android.util.Log.d("ReZeroIzle", "GDrive fileId=$fileId")

        callback(
            newExtractorLink(
                source = name,
                name   = "Google Drive",
                url    = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                type   = ExtractorLinkType.VIDEO,
            ) {
                quality = Qualities.Unknown.value
                referer = "https://drive.google.com/"
            }
        )
        return true
    }
}
