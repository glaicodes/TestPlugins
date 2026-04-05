package com.a.anizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Video link chain:
 *  Step 1: Episode HTML → translator="URL" attrs
 *  Step 2: GET translator URL (XHR) → JSON {data: html} → video="URL" buttons
 *  Step 3: numId from video URL → WebViewResolver loads anizm.net/player/{numId}
 *          (real WebView bypasses CF) → intercepts anizmplayer.com/video/{hash}
 *  Step 4a (Aincrad): POST anizmplayer.com/player/index.php?data={hash}&do=getVideo
 *  Step 4b (GDrive):  GET anizmplayer.com/player/{hash} → drive file ID
 *  Step 4c (others):  GET anizmplayer.com/player/{hash} → iframe → loadExtractor
 */
class AnizleProvider : MainAPI() {

    override var mainUrl    = "https://anizm.net"
    override var name       = "Anizle"
    override var lang       = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val playerBase = "https://anizmplayer.com"
    private val cfKiller   = CloudflareKiller()

    private var csrfToken: String? = null
    private var sessionFetchedAt: Long = 0L
    private val sessionTtlMs = 5 * 60 * 1000L

    private suspend fun getSession() {
        val now = System.currentTimeMillis()
        if (csrfToken != null && (now - sessionFetchedAt) < sessionTtlMs) return
        android.util.Log.d("Anizle", "getSession: refreshing")
        try {
            var resp = app.get(mainUrl, headers = baseHeaders)
            var html = resp.text
            if (isCf(html)) {
                resp = app.get(mainUrl, headers = baseHeaders, interceptor = cfKiller)
                html = resp.text
            }
            csrfToken = Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']""")
                .find(html)?.groupValues?.get(1)
            sessionFetchedAt = System.currentTimeMillis()
            android.util.Log.d("Anizle", "CSRF: ${csrfToken?.take(10)}")
        } catch (e: Exception) {
            android.util.Log.e("Anizle", "getSession error: ${e.message}")
        }
    }

    private fun isCf(html: String) =
        html.contains("Just a moment", ignoreCase = true) ||
        html.contains("cf-browser-verification", ignoreCase = true)

    private val baseHeaders get() = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer"         to "$mainUrl/",
    )
    private val xhrHeaders get() = mapOf(
        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language"  to "tr-TR,tr;q=0.9,en;q=0.7",
        "Origin"           to mainUrl,
        "Referer"          to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept"           to "application/json, text/javascript, */*; q=0.01",
    )

    // ── Search ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }
        getSession()
        val responseText = try {
            val params = mutableMapOf(
                "query" to q, "type" to "detailed", "limit" to "20",
                "priorityField" to "info_title", "orderBy" to "info_year", "orderDirection" to "ASC",
            )
            csrfToken?.let { params["_token"] = it }
            app.get("$mainUrl/searchAnime", headers = xhrHeaders, params = params).text
        } catch (e: Exception) { android.util.Log.e("Anizle", "search error: ${e.message}"); return emptyList() }

        return try {
            val arr = JSONObject(responseText).optJSONArray("data") ?: JSONArray(responseText)
            (0 until arr.length()).mapNotNull { i ->
                val obj   = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug  = obj.optString("info_slug",  "").ifBlank { return@mapNotNull null }
                val title = obj.optString("info_title", "").ifBlank { return@mapNotNull null }
                val thumb = obj.optString("info_poster", "")
                val poster = if (thumb.isBlank()) null
                             else if (thumb.startsWith("http")) thumb
                             else "$mainUrl/storage/pcovers/$thumb"
                newAnimeSearchResponse(title, "$mainUrl/$slug", TvType.Anime) { posterUrl = poster }
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Home page ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "anime-izle" to "Son Eklenen Bölümler",
        ""           to "Son Eklenen Animeler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == "anime-izle") "$mainUrl/anime-izle?sayfa=$page" else "$mainUrl?sayfa=$page"
        val doc = app.get(url, headers = baseHeaders).document

        fun toAbs(src: String): String? {
            if (src.isBlank() || src.startsWith("data:")) return null
            return when {
                src.startsWith("http") -> src
                src.startsWith("//")   -> "https:$src"
                src.startsWith("/")    -> "$mainUrl$src"
                else                   -> "$mainUrl/$src"
            }
        }
        fun clean(raw: String) = raw.replace(Regex("""\s*-\s*Anizm[.\w]*$""", RegexOption.IGNORE_CASE), "").trim()

        val items = doc.select("a.imgWrapperLink, div.posterBlock > a, a[href*=-bolum]")
            .ifEmpty { doc.select("a[href*=-izle]").filter { it.selectFirst("img") != null } }
            .distinctBy { it.attr("href") }
            .mapNotNull { el ->
                val href = el.attr("abs:href").ifBlank { return@mapNotNull null }
                val isEp = href.contains("-bolum")
                val img  = el.selectFirst("img")
                val poster = img?.let { toAbs(it.attr("src")) ?: toAbs(it.attr("data-src")) ?: toAbs(it.attr("data-original")) }
                    ?: el.selectFirst("div.poster")?.let { Regex("""<!--src="([^"]+)"""").find(it.html())?.groupValues?.get(1)?.let { s -> toAbs(s) } }
                val title = if (isEp) {
                    el.parent()?.select("a[href]:not([href*=-bolum])")?.firstOrNull { it.attr("abs:href") != href }
                        ?.text()?.trim()?.let { clean(it) }?.ifBlank { null }
                        ?: img?.attr("alt")?.replace(Regex("""\s*\d+\.?\s*[Bb][oöô]l[uüû]m.*$"""), "")?.let { clean(it) }?.ifBlank { null }
                } else {
                    el.selectFirst("div.title, .truncateText, h4, h5, h3, strong, b")
                        ?.clone()?.also { it.select("span.tag, span.label, .tag, .genres").remove() }
                        ?.text()?.trim()?.let { clean(it) }?.ifBlank { img?.attr("alt")?.let { clean(it) } }?.ifBlank { null }
                } ?: return@mapNotNull null
                val animeUrl = if (isEp) href.replace(Regex("""-\d+[-.]?bolum[^/?#]*"""), "").trimEnd('-', '/') else href
                newAnimeSearchResponse(title, animeUrl, TvType.Anime) { posterUrl = poster }
            }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        getSession()
        val doc = app.get(url, headers = baseHeaders).document
        val title = doc.selectFirst("h2.anizm_pageTitle, h1")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ")
        val posterEl = doc.selectFirst("div.infoPosterImg > img, img[src*=pcovers]")
        val poster = posterEl?.attr("abs:src")?.ifBlank { posterEl.attr("data-src") }?.ifBlank { null }
        val plot = doc.selectFirst("div.infoDesc, .anime-description, .description")
            ?.also { it.select("h1,h2,h3,h4,a").remove() }?.text()?.trim()?.ifBlank { null }
        val year = doc.select("span.dataValue, li, td").map { it.text().trim() }
            .firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        val tags = doc.select("span.dataValue > span.tag > span.label, a[href*=/kategoriler/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.take(4).ifEmpty { null }

        val allLinks = doc.select("div#episodesMiddle a[href]")
            .ifEmpty { doc.select("div.episodeListTabContent a[href]") }
            .distinctBy { it.attr("abs:href") }
        val episodes = run {
            val regular = mutableListOf<Episode>(); val ovas = mutableListOf<Episode>()
            for (el in allLinks) {
                val href  = el.attr("abs:href").ifBlank { continue }
                val label = el.text().trim().ifBlank { continue }
                if (href.contains("fragman") || label.lowercase().contains("fragman")) continue
                if (href.contains("-pv") || label.lowercase().let { it.contains(" pv") || it == "pv" }) continue
                val ep = newEpisode(href) { name = label }
                if (href.contains("ova") || label.lowercase().contains("ova")) ovas.add(ep) else regular.add(ep)
            }
            regular.mapIndexed { i, ep ->
                val num = Regex("""(\d+)[.\s]*[Bb]ölüm""").find(ep.name ?: "")?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""-(\d+)[-.]?bolum""").find(ep.data)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (i + 1)
                ep.apply { episode = num }
            } + ovas
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
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
        getSession()
        android.util.Log.d("Anizle", "loadLinks: $data")

        val epHtml = try { app.get(data, headers = baseHeaders).text }
            catch (e: Exception) { android.util.Log.e("Anizle", "Episode page error: ${e.message}"); return false }
        android.util.Log.d("Anizle", "Episode page len=${epHtml.length}")

        // Step 1: translator buttons
        val translators = mutableListOf<Pair<String, String>>()
        Regex("""translator="([^"]+)"[^>]*data-fansub-name="([^"]*)"""")
            .findAll(epHtml).forEach { m ->
                val u = m.groupValues[1]; if (u.isBlank()) return@forEach
                if (translators.none { it.first == u }) translators += u to m.groupValues[2].ifBlank { "Fansub" }
            }
        if (translators.isEmpty())
            Regex("""data-fansub-name="([^"]*)"[^>]*translator="([^"]+)"""")
                .findAll(epHtml).forEach { m ->
                    val u = m.groupValues[2]; if (u.isBlank()) return@forEach
                    if (translators.none { it.first == u }) translators += u to m.groupValues[1].ifBlank { "Fansub" }
                }
        android.util.Log.d("Anizle", "Step1: ${translators.size} translators")
        if (translators.isEmpty()) return false

        var found = false

        for ((trUrl, fansubName) in translators) {
            android.util.Log.d("Anizle", "Step2: $fansubName -> $trUrl")
            val trText = try { app.get(trUrl, headers = xhrHeaders + mapOf("Referer" to data)).text }
                catch (e: Exception) { android.util.Log.e("Anizle", "Translator fetch: ${e.message}"); continue }
            android.util.Log.d("Anizle", "Step2 len=${trText.length}")

            val trHtml = try { JSONObject(trText).optString("data", "") } catch (_: Exception) { "" }
            if (trHtml.isBlank()) { android.util.Log.w("Anizle", "Step2: no data field"); continue }

            val videos = mutableListOf<Pair<String, String>>()
            Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
                .findAll(trHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
            if (videos.isEmpty())
                Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)"""")
                    .findAll(trHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }
            android.util.Log.d("Anizle", "Step2: ${videos.size} videos")

            for ((videoUrl, videoName) in videos) {
                val vl = videoName.lowercase()
                val isAincrad = vl.contains("aincrad")
                val isGdrive  = vl.contains("gdrive") || vl.contains("google")
                val isKnown = isAincrad || isGdrive ||
                    vl.contains("voe") || vl.contains("filemoon") || vl.contains("uqload") ||
                    vl.contains("vidmoly") || vl.contains("sibnet") || vl.contains("sendvid") ||
                    vl.contains("doodstream") || vl.contains("ok.ru") ||
                    vl.contains("odnoklassniki") || vl.contains("sistenn")
                if (!isKnown) { android.util.Log.d("Anizle", "Step3: skip unknown $videoName"); continue }

                // ── Step 3: get hash via WebViewResolver ──────────────────────
                // anizm.net/video/{id} XHR and anizm.net/player/{id} page are
                // both CF-blocked from the HTTP client. WebViewResolver uses a
                // real Android WebView that passes CF naturally.
                // The numId is already in the video URL — no extra request needed.
                // We intercept the anizmplayer.com/video/{hash} iframe load.
                val numId = Regex("""/video/(\d+)""").find(videoUrl)?.groupValues?.get(1)
                if (numId == null) {
                    android.util.Log.w("Anizle", "Step3: no numId in $videoUrl"); continue
                }
                val playerPageUrl = "$mainUrl/player/$numId"
                android.util.Log.d("Anizle", "Step3: WebView $playerPageUrl for $videoName")

                val webViewResult = try {
                    WebViewResolver(
                        Regex("""anizmplayer\.com/(?:video|player)/[a-f0-9]{32}""")
                    ).resolveUrl(playerPageUrl, mapOf("Referer" to data))
                } catch (e: Exception) {
                    android.util.Log.e("Anizle", "Step3 WebView error: ${e.message}"); null
                }

                val hash = webViewResult?.first?.let {
                    Regex("""([a-f0-9]{32})""").find(it)?.groupValues?.get(1)
                }
                if (hash == null) {
                    android.util.Log.w("Anizle", "Step3: no hash for $videoName (result=${webViewResult?.first})"); continue
                }
                android.util.Log.d("Anizle", "Step3: hash=$hash for $videoName")

                val label = "$fansubName - ${videoName.replace("(Reklamsız)", "").replace("(reklamsız)", "").trim()}"
                val playerRef = "$playerBase/player/$hash"

                // ── Step 4a: Aincrad ──────────────────────────────────────────
                if (isAincrad) {
                    val aHeaders = mapOf(
                        "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept"           to "application/json, */*; q=0.01",
                        "Referer"          to playerRef,
                        "Origin"           to playerBase,
                    )
                    val streamText = try {
                        app.post("$playerBase/player/index.php?data=$hash&do=getVideo", headers = aHeaders).text
                    } catch (e: Exception) { android.util.Log.e("Anizle", "getVideo: ${e.message}"); continue }
                    android.util.Log.d("Anizle", "Step4a: ${streamText.take(300)}")

                    val json = try { JSONObject(streamText) } catch (_: Exception) { continue }
                    val securedLink = json.optString("securedLink", "")
                    val videoSource = json.optString("videoSource", "")

                    if (json.optBoolean("hls", false) && securedLink.isNotBlank()) {
                        callback(newExtractorLink(source = label, name = label, url = securedLink,
                            type = ExtractorLinkType.M3U8) { quality = Qualities.P1080.value; referer = playerRef })
                        found = true; continue
                    }
                    if (videoSource.isNotBlank()) {
                        callback(newExtractorLink(source = label, name = label, url = videoSource,
                            type = ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                        found = true
                    }
                    continue
                }

                // ── Step 4b/c: fetch anizmplayer.com/player/{hash} (no CF) ────
                val pageHtml = try {
                    app.get(playerRef, headers = baseHeaders + mapOf("Referer" to "$mainUrl/")).text
                } catch (e: Exception) { android.util.Log.e("Anizle", "Step4 page: ${e.message}"); continue }
                android.util.Log.d("Anizle", "Step4 page len=${pageHtml.length}")

                // ── Step 4b: GDrive ───────────────────────────────────────────
                if (isGdrive) {
                    val fileId = Regex("""[?&]id=([A-Za-z0-9_-]{25,})""").find(pageHtml)?.groupValues?.get(1)
                        ?: Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,})""").find(pageHtml)?.groupValues?.get(1)
                    if (fileId == null) { android.util.Log.w("Anizle", "Step4b: no fileId"); continue }
                    callback(newExtractorLink(source = label, name = label,
                        url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                        type = ExtractorLinkType.VIDEO) {
                        quality = Qualities.Unknown.value; referer = "https://drive.google.com/"
                    })
                    found = true; continue
                }

                // ── Step 4c: other hosts ──────────────────────────────────────
                val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(pageHtml)?.groupValues?.get(1)
                    ?: Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                        .find(pageHtml)?.groupValues?.get(1)
                android.util.Log.d("Anizle", "Step4c: $videoName embedUrl=$embedUrl")
                if (embedUrl != null) { loadExtractor(embedUrl, mainUrl, subtitleCallback, callback); found = true }
            }
        }
        return found
    }
}
