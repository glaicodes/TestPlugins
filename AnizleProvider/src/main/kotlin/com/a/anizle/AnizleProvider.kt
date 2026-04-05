package com.a.anizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anizle / Anizm CloudStream 3 Provider
 *
 * Video link chain (confirmed from logcat + network capture):
 *  Step 1: Episode HTML → translator="URL" attrs
 *  Step 2: GET translator URL (XHR) → JSON {data: html} → video="URL" buttons
 *  Step 3: /video/{id} is CF-blocked from app; skip it.
 *          Derive /player/{id} from same numeric ID and fetch directly.
 *          /player/{id} is a normal iframe page → packed JS → FirePlayer hash (32 hex)
 *  Step 4: POST anizmplayer.com/player/index.php?data={hash}&do=getVideo → {hls, securedLink}
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
            if (html.contains("Just a moment", ignoreCase = true) ||
                html.contains("cf-browser-verification", ignoreCase = true)) {
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

    // ── Load (anime detail page) ───────────────────────────────────────────────
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
        fun isCf(h: String) = h.contains("Just a moment", ignoreCase = true) || h.contains("cf-browser-verification", ignoreCase = true)

        for ((trUrl, fansubName) in translators) {
            android.util.Log.d("Anizle", "Step2: $fansubName -> $trUrl")
            val trText = try { app.get(trUrl, headers = xhrHeaders + mapOf("Referer" to mainUrl)).text }
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
            if (videos.isEmpty())
                Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
                    .findAll(trText).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
            if (videos.isEmpty() && trUrl == translators.first().first) {
                Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
                    .findAll(epHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
                if (videos.isEmpty())
                    Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)"""")
                        .findAll(epHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }
            }
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

                android.util.Log.d("Anizle", "Step3: $videoName -> $videoUrl")
                val label = "$fansubName - ${videoName.replace("(Reklamsız)", "").replace("(reklamsız)", "").trim()}"

                // ── Step 3: resolve player page ───────────────────────────────
                //
                // CONFIRMED from logcat: /video/{id} returns CF "Just a moment" page
                // even with the CSRF token (CF blocks XHR endpoint from non-browser UA).
                //
                // FIX: derive /player/{id} from the same numeric ID.
                //   /video/{id} would return JSON { player: '<iframe src="/player/{id}">' }
                //   We just construct that URL directly — skips the blocked XHR entirely.
                //
                // Path A: hash embedded in existing HTML (0 requests)
                // Path B: GET anizm.net/player/{id} directly (plain GET)
                // Path C: same URL retried with cfKiller if plain GET returns CF challenge

                var pageHtml = ""

                // Path A: hash already present in cached HTML
                val hashRe = Regex("""anizmplayer\.com/(?:video|player)/([a-f0-9]{32})""")
                val embeddedHash = hashRe.find(trHtml)?.groupValues?.get(1)
                    ?: hashRe.find(trText)?.groupValues?.get(1)
                    ?: hashRe.find(epHtml)?.groupValues?.get(1)
                if (embeddedHash != null) {
                    android.util.Log.d("Anizle", "PathA: hash=$embeddedHash in cached HTML")
                    pageHtml = "anizmplayer.com/video/$embeddedHash"
                }

                // Path B/C: GET anizm.net/player/{numericId} directly
                if (pageHtml.isBlank()) {
                    val numId = Regex("""/video/(\d+)""").find(videoUrl)?.groupValues?.get(1)
                    if (numId == null) {
                        android.util.Log.w("Anizle", "Step3: no numeric ID in $videoUrl"); continue
                    }
                    val playerUrl = "$mainUrl/player/$numId"
                    android.util.Log.d("Anizle", "PathB: GET $playerUrl")

                    // Plain attempt — uses cf_clearance cookie from getSession()
                    var raw = try {
                        app.get(playerUrl, headers = baseHeaders + mapOf("Referer" to "$mainUrl/")).text
                    } catch (e: Exception) { android.util.Log.w("Anizle", "PathB plain: ${e.message}"); "" }
                    android.util.Log.d("Anizle", "PathB plain: len=${raw.length} cf=${isCf(raw)}")

                    // Path C: cfKiller if still challenged
                    if (raw.isBlank() || isCf(raw)) {
                        android.util.Log.d("Anizle", "PathC: cfKiller for $playerUrl")
                        raw = try {
                            app.get(playerUrl,
                                headers = baseHeaders + mapOf("Referer" to "$mainUrl/"),
                                interceptor = cfKiller).text
                        } catch (e: Exception) { android.util.Log.w("Anizle", "PathC cfKiller: ${e.message}"); "" }
                        android.util.Log.d("Anizle", "PathC cfKiller: len=${raw.length} cf=${isCf(raw)}")
                    }

                    if (raw.isNotBlank() && !isCf(raw)) pageHtml = raw
                }

                if (pageHtml.isBlank()) {
                    android.util.Log.w("Anizle", "Step3: all paths failed for $videoName")
                    continue
                }

                // ── GDrive ────────────────────────────────────────────────────
                if (isGdrive) {
                    val fileId = Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,})""").find(pageHtml)?.groupValues?.get(1)
                        ?: Regex("""[?&]id=([A-Za-z0-9_-]{25,})""").find(pageHtml)?.groupValues?.get(1)
                    if (fileId == null) { android.util.Log.w("Anizle", "GDrive: no fileId"); continue }
                    callback(newExtractorLink(source = label, name = label,
                        url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                        type = ExtractorLinkType.VIDEO) {
                        quality = Qualities.Unknown.value; referer = "https://drive.google.com/"
                    })
                    found = true; continue
                }

                // ── Generic hosts (Voe, FileMoon, UQload, etc.) ───────────────
                if (!isAincrad) {
                    val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(pageHtml)?.groupValues?.get(1)
                        ?: Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                            .find(pageHtml)?.groupValues?.get(1)
                    android.util.Log.d("Anizle", "loadExtractor: $videoName embedUrl=$embedUrl")
                    if (embedUrl != null) { loadExtractor(embedUrl, mainUrl, subtitleCallback, callback); found = true }
                    continue
                }

                // ── Aincrad / FirePlayer ──────────────────────────────────────
                val fireId = extractFireplayerId(pageHtml)
                android.util.Log.d("Anizle", "Step4: fireId=$fireId pageHtml len=${pageHtml.length}")
                fireId ?: continue

                val playerRef = "$playerBase/player/$fireId"
                val aHeaders = mapOf(
                    "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept"           to "application/json, */*; q=0.01",
                    "Referer"          to playerRef,
                    "Origin"           to playerBase,
                )
                val streamText = try { app.post("$playerBase/player/index.php?data=$fireId&do=getVideo", headers = aHeaders).text }
                    catch (e: Exception) { android.util.Log.e("Anizle", "getVideo: ${e.message}"); continue }
                android.util.Log.d("Anizle", "Step5: ${streamText.take(300)}")

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
            }
        }
        return found
    }

    // ── JS helpers ────────────────────────────────────────────────────────────
    private fun extractFireplayerId(html: String): String? {
        Regex("""anizmplayer\.com/(?:video|player)/([a-f0-9]{32})""")
            .find(html)?.groupValues?.get(1)?.let { return it }
        val evalMatch = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?return p\}\('(.*?)',(\d+),(\d+),'([^']+)'\.split\('\|'\),0,\{\}\)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(html)
        if (evalMatch != null) {
            runCatching {
                val decoded = unpackJs(evalMatch.groupValues[1], evalMatch.groupValues[2].toInt(),
                    evalMatch.groupValues[3].toInt(), evalMatch.groupValues[4].split("|"))
                Regex("""FirePlayer\s*\(\s*["']([a-f0-9]{32})["']""").find(decoded)
                    ?.groupValues?.get(1)?.let { return it }
            }
        }
        return Regex("""FirePlayer\(["']([a-f0-9]{32})["']""").find(html)?.groupValues?.get(1)
    }

    private fun unpackJs(p: String, a: Int, c: Int, k: List<String>): String {
        fun toBase(n: Int, base: Int): String {
            val head = if (n < base) "" else toBase(n / base, base)
            val rem  = n % base
            return head + when { rem > 35 -> (rem + 29).toChar(); rem > 9 -> (rem + 87).toChar(); else -> ('0' + rem) }
        }
        var idx = c; val dict = mutableMapOf<String, String>()
        while (idx-- > 0) { val key = toBase(idx, a); dict[key] = if (idx < k.size && k[idx].isNotEmpty()) k[idx] else key }
        return Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value }
    }
}
