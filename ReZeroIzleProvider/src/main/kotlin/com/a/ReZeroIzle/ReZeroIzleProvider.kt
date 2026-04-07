package com.a.rezeroizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ReZero İzle CloudStream 3 Provider
 *
 * Season index HTML: <div class="hub-card"><ul><li><a href="...">
 * Episode GDrive:    <a id="downloadBtn" href="...&amp;id=FILE_ID">
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

        val seasonNum = Regex("""/sezon/(\d+)/""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val doc = try {
            app.get(url, headers = baseHeaders).document
        } catch (e: Exception) {
            android.util.Log.e("ReZeroIzle", "Season $seasonNum fetch failed: ${e.message}")
            return newAnimeLoadResponse(title, url, type) {
                posterUrl = poster; this.plot = plot
                addEpisodes(DubStatus.Subbed, emptyList())
            }
        }

        val episodes = mutableListOf<Episode>()

        // FIX — WEBSITE ORDERING:
        // Use a pure sequential counter for ALL episode numbers.
        // Previously we tried to extract numbers from URLs, which caused bolum/1 and
        // arabolum/1 to both get episode=1, making CloudStream sort them unpredictably.
        // Sequential counter preserves the exact order the site shows them.
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
                    this.episode = counter   // pure sequential — preserves website order
                }
            )
            android.util.Log.d("ReZeroIzle", "S$seasonNum E$counter [$href]: $label")
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
            app.get(data, headers = baseHeaders).text
        } catch (e: Exception) {
            android.util.Log.e("ReZeroIzle", "Episode fetch error: ${e.message}")
            return false
        }
        android.util.Log.d("ReZeroIzle", "Episode html len=${html.length}")

        // ── Step 1: parse page, extract EPISODE_INDEX and external script URLs ──
        val doc2 = org.jsoup.Jsoup.parse(html)

        // Extract window.EPISODE_INDEX from inline scripts
        var episodeIndex = -1
        doc2.select("script:not([src])").forEach { el ->
            val m = Regex("""window\.EPISODE_INDEX\s*=\s*(\d+)""").find(el.html())
            if (m != null) episodeIndex = m.groupValues[1].toInt()
        }
        android.util.Log.d("ReZeroIzle", "EPISODE_INDEX=$episodeIndex")

        // Log and collect external script URLs from this domain
        val extScripts = doc2.select("script[src]")
            .map { it.attr("abs:src").ifBlank { it.attr("src") } }
            .filter { it.isNotBlank() }
        android.util.Log.d("ReZeroIzle", "External scripts: $extScripts")

        // ── Step 2: fetch external scripts and search for GDrive IDs ──
        var fileId: String? = null

        for (scriptUrl in extScripts) {
            val absUrl = when {
                scriptUrl.startsWith("http") -> scriptUrl
                scriptUrl.startsWith("/")    -> "$mainUrl$scriptUrl"
                else                         -> "$mainUrl/$scriptUrl"
            }
            android.util.Log.d("ReZeroIzle", "Fetching script: $absUrl")
            val jsText = try {
                app.get(absUrl, headers = baseHeaders).text
            } catch (e: Exception) {
                android.util.Log.w("ReZeroIzle", "Script fetch failed: ${e.message}")
                continue
            }
            android.util.Log.d("ReZeroIzle", "Script len=${jsText.length} preview=${jsText.take(200)}")

            // Collect ALL GDrive file IDs from this script
            val ids = Regex("""["'`]([A-Za-z0-9_-]{25,})["'`]""")
                .findAll(jsText)
                .map { it.groupValues[1] }
                .filter { it.matches(Regex("[A-Za-z0-9_-]{25,}")) }
                .toList()
            android.util.Log.d("ReZeroIzle", "Found ${ids.size} candidate IDs in script")

            if (ids.isNotEmpty()) {
                // Use EPISODE_INDEX if valid, otherwise take first
                fileId = if (episodeIndex >= 0 && episodeIndex < ids.size) {
                    android.util.Log.d("ReZeroIzle", "Using index $episodeIndex: ${ids[episodeIndex]}")
                    ids[episodeIndex]
                } else {
                    android.util.Log.d("ReZeroIzle", "Index out of range, using first: ${ids[0]}")
                    ids[0]
                }
                break
            }
        }

        // ── Step 3: fallback — search inline HTML for any GDrive pattern ──
        if (fileId == null) {
            fileId =
                Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,})""").find(html)?.groupValues?.get(1)
                ?: Regex("""[?&](?:amp;)?id=([A-Za-z0-9_-]{25,})""").find(html)?.groupValues?.get(1)
        }

        if (fileId == null) {
            android.util.Log.w("ReZeroIzle", "No GDrive ID found anywhere. episodeIndex=$episodeIndex extScripts=$extScripts")
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
