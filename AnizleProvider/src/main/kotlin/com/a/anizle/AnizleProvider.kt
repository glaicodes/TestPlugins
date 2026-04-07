package com.a.anizle

import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AnizleProvider : MainAPI() {

    override var mainUrl    = "https://anizm.net"
    override var name       = "Anizle"
    override var lang       = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // Auto-detected from intercepted URLs; falls back to known domain
    private var playerBase = "https://anizmplayer.com"
    private val cfKiller   = CloudflareKiller()
    private var csrfToken: String? = null
    private var sessionFetchedAt: Long = 0L
    private val sessionTtlMs = 5 * 60 * 1000L

    private fun log(msg: String) { android.util.Log.d("Anizle", msg) }

    private suspend fun getSession() {
        val now = System.currentTimeMillis()
        if (csrfToken != null && (now - sessionFetchedAt) < sessionTtlMs) return
        try {
            var resp = app.get(mainUrl, headers = baseHeaders)
            var html = resp.text
            if (isCf(html)) { resp = app.get(mainUrl, headers = baseHeaders, interceptor = cfKiller); html = resp.text }
            csrfToken = Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']""").find(html)?.groupValues?.get(1)
            // Auto-detect player domain from homepage if present
            Regex("""(https?://[a-z0-9]+player[a-z0-9]*\.com)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let {
                if (it != playerBase) { log("session: player domain changed to $it"); playerBase = it }
            }
            sessionFetchedAt = System.currentTimeMillis()
        } catch (_: Exception) {}
    }

    private fun isCf(html: String) = html.contains("Just a moment", true) || html.contains("cf-browser-verification", true) || html.contains("challenge-platform", true)

    private val baseHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7", "Referer" to "$mainUrl/")
    private val xhrHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7", "Origin" to mainUrl, "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/javascript, */*; q=0.01")

    // ── Attribute extraction helpers ─────────────────────────────────────────
    // Resilient: tries multiple patterns for translator/video attributes
    private fun extractAttrPairs(html: String, attr1: String, attr2: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        // Pattern 1: attr1="v1" ... attr2="v2" (same tag)
        Regex("""$attr1="([^"]+)"[^>]*$attr2="([^"]*)"""").findAll(html).forEach { m ->
            results += m.groupValues[1] to m.groupValues[2]
        }
        // Pattern 2: attr2="v2" ... attr1="v1" (reversed order)
        if (results.isEmpty()) Regex("""$attr2="([^"]*)"[^>]*$attr1="([^"]+)"""").findAll(html).forEach { m ->
            results += m.groupValues[2] to m.groupValues[1]
        }
        return results.distinctBy { it.first }
    }

    // ── Batch embed resolver ────────────────────────────────────────────────
    // Returns: numId -> "ap:{hash}" | "gd:{fileId}"
    // Auto-detects player domain from intercepted URLs
    private suspend fun resolveEmbeds(numIds: List<String>, episodeUrl: String): Map<String, String> {
        if (numIds.isEmpty()) return emptyMap()
        val results = mutableMapOf<String, String>()
        // Flexible: match any *player*.com domain with hex hash (adapts if domain changes)
        val apRe = Regex("""(https?://[a-z0-9]*player[a-z0-9]*\.[a-z.]+)/(?:video|player)/([a-f0-9]{24,40})""", RegexOption.IGNORE_CASE)
        val gdRe = Regex("""drive\.google\.com/(?:file/d/|uc\?[^"]*id=|open\?[^"]*id=)([A-Za-z0-9_-]{20,})""")

        return suspendCoroutine { cont ->
            val handler = android.os.Handler(Looper.getMainLooper())
            handler.post {
                var done = false
                val ctx = try { com.lagradost.cloudstream3.AcraApplication.context } catch (_: Exception) { null }
                if (ctx == null) { log("resolve: no context"); cont.resume(emptyMap()); return@post }

                val wv = WebView(ctx).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                fun finish() {
                    if (!done) { done = true; try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}; cont.resume(results) }
                }

                val globalTimeout = Runnable { log("resolve: global timeout (${results.size}/${numIds.size})"); finish() }
                handler.postDelayed(globalTimeout, 40_000L)

                var currentTarget = ""; var currentIdx = -1
                val perIdTimeout = arrayOfNulls<Runnable>(1)
                var usedFallback = false

                fun resolveNext() {
                    perIdTimeout[0]?.let { handler.removeCallbacks(it) }
                    currentIdx++
                    if (currentIdx == 1 && results.isEmpty() && !usedFallback) {
                        log("resolve: first failed, falling back to loadUrl")
                        usedFallback = true; currentIdx = -1
                        wv.loadUrl(episodeUrl)
                        return
                    }
                    if (currentIdx >= numIds.size || done) { handler.removeCallbacks(globalTimeout); finish(); return }
                    currentTarget = numIds[currentIdx]
                    val nid = numIds[currentIdx]
                    log("resolve: [${currentIdx}/${numIds.size}] numId=$nid")

                    perIdTimeout[0] = Runnable { if (currentTarget == nid && !done) { log("resolve: timeout $nid"); resolveNext() } }
                    handler.postDelayed(perIdTimeout[0]!!, 8_000L)

                    val delay = if (currentIdx == 0) 0L else (500L + (Math.random() * 1000).toLong())
                    handler.postDelayed({
                        if (done) return@postDelayed
                        wv.evaluateJavascript("""
                            (function(){var old=document.getElementById('_pf');if(old)old.remove();
                            var f=document.createElement('iframe');f.id='_pf';
                            f.style.cssText='width:1px;height:1px;position:absolute;left:-9999px';
                            f.src='/player/$nid';document.body.appendChild(f);})();
                        """.trimIndent(), null)
                    }, delay)
                }

                wv.addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun h(v: String) {
                        val tgt = currentTarget
                        if (v.isNotBlank() && tgt.isNotBlank() && !results.containsKey(tgt)) {
                            log("resolve: $v for numId=$tgt"); results[tgt] = v
                        }
                        handler.post { resolveNext() }
                    }
                }, "_b")

                var pageReady = false
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        // Intercept player domain (auto-detects domain changes)
                        apRe.find(url)?.let { m ->
                            val domain = m.groupValues[1]; val hash = m.groupValues[2]
                            // Auto-update playerBase if domain changed
                            if (!playerBase.contains(domain.substringAfter("://"))) {
                                playerBase = domain; log("resolve: player domain updated to $domain")
                            }
                            handler.post { wv.evaluateJavascript("_b.h('ap:$hash')", null) }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        // Intercept Google Drive (multiple URL formats)
                        gdRe.find(url)?.let { m ->
                            log("resolve: GDrive: ${m.groupValues[1]}")
                            handler.post { wv.evaluateJavascript("_b.h('gd:${m.groupValues[1]}')", null) }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        // Block ALL /player/ EXCEPT current target
                        if (url.contains("/player/")) {
                            val tgt = currentTarget
                            if (tgt.isBlank() || !url.endsWith("/player/$tgt"))
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        val l = url.lowercase()
                        if (l.contains("statbest") || l.contains("analytics") || l.contains("adservice") || l.contains("doubleclick"))
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        log("resolve: pageFinished url=$url ready=$pageReady fallback=$usedFallback")
                        if (!pageReady) {
                            pageReady = true
                            if (usedFallback && url?.contains("anizm") == true) {
                                log("resolve: fallback loaded, restarting"); resolveNext()
                            }
                        }
                    }
                }

                log("resolve: loading blank with base=$episodeUrl")
                wv.loadDataWithBaseURL(episodeUrl, "<html><body></body></html>", "text/html", "utf-8", null)
                handler.post { resolveNext() }
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().ifBlank { return emptyList() }; getSession()
        val responseText = try {
            val params = mutableMapOf("query" to q, "type" to "detailed", "limit" to "20",
                "priorityField" to "info_title", "orderBy" to "info_year", "orderDirection" to "ASC")
            csrfToken?.let { params["_token"] = it }
            // Try primary search endpoint, fall back to alternative
            try { app.get("$mainUrl/searchAnime", headers = xhrHeaders, params = params).text }
            catch (_: Exception) { app.get("$mainUrl/search", headers = xhrHeaders, params = params).text }
        } catch (_: Exception) { return emptyList() }
        return try {
            val arr = JSONObject(responseText).optJSONArray("data") ?: JSONArray(responseText)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = obj.optString("info_slug", "").ifBlank { obj.optString("slug", "") }.ifBlank { return@mapNotNull null }
                val title = obj.optString("info_title", "").ifBlank { obj.optString("title", "") }.ifBlank { return@mapNotNull null }
                val thumb = obj.optString("info_poster", "").ifBlank { obj.optString("poster", "") }
                val poster = if (thumb.isBlank()) null
                    else if (thumb.startsWith("http")) thumb
                    else if (thumb.startsWith("/")) "$mainUrl$thumb"
                    else "$mainUrl/storage/pcovers/$thumb"
                newAnimeSearchResponse(title, "$mainUrl/$slug", TvType.Anime) { posterUrl = poster }
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── Home page ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf("anime-izle" to "Son Eklenen Bölümler", "" to "Son Eklenen Animeler")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == "anime-izle") "$mainUrl/anime-izle?sayfa=$page" else "$mainUrl?sayfa=$page"
        val doc = app.get(url, headers = baseHeaders).document
        fun toAbs(src: String): String? {
            if (src.isBlank() || src.startsWith("data:")) return null
            return when { src.startsWith("http") -> src; src.startsWith("//") -> "https:$src"; src.startsWith("/") -> "$mainUrl$src"; else -> "$mainUrl/$src" }
        }
        fun clean(raw: String) = raw.replace(Regex("""\s*[-–]\s*Anizm[.\w]*$""", RegexOption.IGNORE_CASE), "").trim()

        // Broad selector chain: tries specific → generic
        val items = doc.select("a.imgWrapperLink, div.posterBlock > a, a[href*=-bolum], a[href*=-izle]")
            .filter { it.selectFirst("img") != null || it.attr("href").contains("-bolum") }
            .distinctBy { it.attr("href") }.mapNotNull { el ->
                val href = el.attr("abs:href").ifBlank { return@mapNotNull null }
                val isEp = href.contains("-bolum") || href.contains("-izle")
                val img = el.selectFirst("img")
                val poster = img?.let {
                    toAbs(it.attr("src")) ?: toAbs(it.attr("data-src"))
                    ?: toAbs(it.attr("data-original")) ?: toAbs(it.attr("data-lazy-src"))
                } ?: el.selectFirst("[style*=background]")?.let {
                    Regex("""url\(['"]?([^'")\s]+)""").find(it.attr("style"))?.groupValues?.get(1)?.let { s -> toAbs(s) }
                }
                val title = if (isEp) {
                    el.parent()?.select("a[href]:not([href*=-bolum]):not([href*=-izle])")?.firstOrNull { it.attr("abs:href") != href }
                        ?.text()?.trim()?.let { clean(it) }?.ifBlank { null }
                        ?: img?.attr("alt")?.replace(Regex("""\s*\d+\.?\s*[Bb][oöô]l[uüû]m.*$"""), "")?.let { clean(it) }?.ifBlank { null }
                } else {
                    // Try multiple selectors for title
                    (el.selectFirst("div.title, .truncateText, h4, h5, h3, h2, strong, b, .name, .anime-name")
                        ?.clone()?.also { it.select("span.tag, span.label, .tag, .genres, .meta, .info").remove() }
                        ?.text()?.trim()?.let { clean(it) }?.ifBlank { null })
                        ?: img?.attr("alt")?.let { clean(it) }?.ifBlank { null }
                        ?: el.attr("title")?.let { clean(it) }?.ifBlank { null }
                } ?: return@mapNotNull null
                val animeUrl = if (href.contains("-bolum")) href.replace(Regex("""-\d+[-.]?bolum[^/?#]*"""), "").trimEnd('-', '/') else href
                newAnimeSearchResponse(title, animeUrl, TvType.Anime) { posterUrl = poster }
            }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        getSession(); val doc = app.get(url, headers = baseHeaders).document
        val title = doc.selectFirst("h2.anizm_pageTitle, h2.page-title, h1, .anime-title, [itemprop=name]")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ")
        val posterEl = doc.selectFirst("div.infoPosterImg > img, img[src*=pcovers], img[src*=poster], .poster img, [itemprop=image]")
        val poster = posterEl?.let { it.attr("abs:src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("data-original") }.ifBlank { null } }
        val plot = doc.selectFirst("div.infoDesc, .anime-description, .description, .synopsis, [itemprop=description]")
            ?.also { it.select("h1,h2,h3,h4,a,script").remove() }?.text()?.trim()?.ifBlank { null }
        val year = doc.select("span.dataValue, .info-value, li, td").map { it.text().trim() }
            .firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        val tags = doc.select("span.dataValue > span.tag > span.label, a[href*=/kategoriler/], a[href*=/genre/], .genre a")
            .map { it.text().trim() }.filter { it.isNotBlank() && it.length < 30 }.take(6).ifEmpty { null }

        // Broad episode selectors
        val allLinks = doc.select("div#episodesMiddle a[href]")
            .ifEmpty { doc.select("div.episodeListTabContent a[href]") }
            .ifEmpty { doc.select(".episode-list a[href], .episodes a[href], a[href*=-bolum]") }
            .distinctBy { it.attr("abs:href") }
        val episodes = allLinks.mapNotNull { el ->
            val href = el.attr("abs:href").ifBlank { return@mapNotNull null }
            val label = el.text().trim().ifBlank { return@mapNotNull null }
            val ll = label.lowercase()
            if (href.contains("fragman") || ll.contains("fragman")) return@mapNotNull null
            if (href.contains("-pv-") || ll == "pv" || ll.endsWith(" pv")) return@mapNotNull null
            newEpisode(href) { name = label }
        }.mapIndexed { i, ep -> ep.apply { episode = i + 1 } }
        log("load: ${episodes.size} episodes for $title")
        return newAnimeLoadResponse(title, url, TvType.Anime) { posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags; addEpisodes(DubStatus.Subbed, episodes) }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        getSession(); log("loadLinks: $data")

        val epHtml = try { app.get(data, headers = baseHeaders).text } catch (e: Exception) { log("loadLinks: page error: ${e.message}"); return false }
        log("loadLinks: page len=${epHtml.length}")

        // Extract translators — resilient to attribute order changes
        val translators = extractAttrPairs(epHtml, "translator", "data-fansub-name")
            .map { it.first to it.second.ifBlank { "Fansub" } }
            .toMutableList()
        log("loadLinks: ${translators.size} translators: ${translators.map { it.second }}")
        if (translators.isEmpty()) return false

        data class VidInfo(val numId: String, val name: String, val fansub: String)
        val allWanted = mutableListOf<VidInfo>()
        for ((trUrl, fansubName) in translators) {
            val trText = try { app.get(trUrl, headers = xhrHeaders + mapOf("Referer" to data)).text }
                catch (e: Exception) { log("loadLinks: tr error ($fansubName): ${e.message}"); continue }
            // Try JSON data field, fall back to raw HTML
            val trHtml = try { JSONObject(trText).optString("data", "") } catch (_: Exception) { trText }
            if (trHtml.isBlank()) continue

            // Extract video buttons — resilient to attribute order
            val videos = extractAttrPairs(trHtml, "video", "data-video-name")
                .map { it.first to it.second.ifBlank { "Player" } }
            log("loadLinks: $fansubName: ${videos.map { it.second }}")

            for ((videoUrl, videoName) in videos) {
                val vl = videoName.lowercase()
                if (!vl.contains("aincrad") && !vl.contains("gdrive") && !vl.contains("google") && !vl.contains("drive")) continue
                // Flexible numId extraction
                val numId = Regex("""/video/(\d+)""").find(videoUrl)?.groupValues?.get(1)
                    ?: Regex("""(\d{4,})""").find(videoUrl)?.groupValues?.get(1)
                    ?: continue
                allWanted.add(VidInfo(numId, videoName, fansubName))
            }
        }
        log("loadLinks: ${allWanted.size} wanted")
        if (allWanted.isEmpty()) return false

        val uniqueIds = allWanted.map { it.numId }.distinct()
        log("loadLinks: resolving ${uniqueIds.size} numIds")
        val embedMap = try { resolveEmbeds(uniqueIds, data) } catch (e: Exception) { log("loadLinks: resolve error: ${e.message}"); emptyMap() }
        log("loadLinks: resolved ${embedMap.size}/${uniqueIds.size}: $embedMap")

        var found = false
        for (vi in allWanted) {
            val embed = embedMap[vi.numId]
            if (embed == null) { log("step4: ${vi.fansub}/${vi.name} MISSING"); continue }
            val label = "${vi.fansub} - ${vi.name.replace(Regex("""\([Rr]eklamsız\)"""), "").trim()}"
            log("step4: $label embed=$embed")

            if (embed.startsWith("ap:")) {
                val hash = embed.removePrefix("ap:")
                val playerRef = "$playerBase/player/$hash"
                val aHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, */*; q=0.01", "Referer" to playerRef, "Origin" to playerBase)
                // Try primary endpoint, fall back to alternatives
                val streamText = try {
                    app.post("$playerBase/player/index.php?data=$hash&do=getVideo", headers = aHeaders).text
                } catch (_: Exception) {
                    try { app.post("$playerBase/player/ajax.php?data=$hash&do=getVideo", headers = aHeaders).text }
                    catch (e: Exception) { log("aincrad error: ${e.message}"); continue }
                }
                val json = try { JSONObject(streamText) } catch (_: Exception) { continue }
                val securedLink = json.optString("securedLink", "").ifBlank { json.optString("videoUrl", "") }
                val videoSource = json.optString("videoSource", "").ifBlank { json.optString("source", "") }
                log("aincrad: hls=${json.optBoolean("hls")} secured=${securedLink.isNotBlank()} source=${videoSource.isNotBlank()}")
                if (json.optBoolean("hls", false) && securedLink.isNotBlank()) {
                    callback(newExtractorLink(source = label, name = label, url = securedLink, type = ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value; referer = playerRef; headers = mapOf("Origin" to playerBase, "Referer" to playerRef) })
                    found = true
                } else if (videoSource.isNotBlank()) {
                    callback(newExtractorLink(source = label, name = label, url = videoSource, type = ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value; referer = playerRef })
                    found = true
                }
                continue
            }

            if (embed.startsWith("gd:")) {
                val fileId = embed.removePrefix("gd:")
                log("gdrive: fileId=$fileId for $label")
                callback(newExtractorLink(source = label, name = label,
                    url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                    type = ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value; referer = "https://drive.google.com/" })
                found = true
                continue
            }
        }
        log("loadLinks: done, found=$found")
        return found
    }
}
