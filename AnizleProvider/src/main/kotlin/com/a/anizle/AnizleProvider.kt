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

    private var playerBase = "https://anizmplayer.com"
    private val cfKiller   = CloudflareKiller()
    private var csrfToken: String? = null
    private var sessionFetchedAt: Long = 0L
    private val sessionTtlMs = 5 * 60 * 1000L

    // Hash cache — numId → embed string, with timestamps for TTL
    // Hashes are content-based (not session-based) so safe to cache
    private data class CachedEmbed(val embed: String, val time: Long)
    private val embedCache = mutableMapOf<String, CachedEmbed>()
    private val cacheTtlMs = 30 * 60 * 1000L // 30 minutes

    private fun getCached(numId: String): String? {
        val c = embedCache[numId] ?: return null
        if (System.currentTimeMillis() - c.time > cacheTtlMs) { embedCache.remove(numId); return null }
        return c.embed
    }
    private fun putCache(numId: String, embed: String) {
        embedCache[numId] = CachedEmbed(embed, System.currentTimeMillis())
        // Evict old entries if cache grows too large
        if (embedCache.size > 200) {
            val now = System.currentTimeMillis()
            val iter = embedCache.iterator()
            while (iter.hasNext()) { if (now - iter.next().value.time > cacheTtlMs) iter.remove() }
        }
    }

    // Pre-compiled regexes — avoid recompilation in hot paths
    private val apRe = Regex("""(https?://[a-z0-9]*player[a-z0-9]*\.[a-z.]+)/(?:video|player)/([a-f0-9]{24,40})""", RegexOption.IGNORE_CASE)
    private val gdRe = Regex("""drive\.google\.com/(?:file/d/|uc\?[^"]*id=|open\?[^"]*id=)([A-Za-z0-9_-]{20,})""")
    private val csrfRe1 = Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']""")
    private val csrfRe2 = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']""")
    private val numIdRe = Regex("""/video/(\d+)""")
    private val trRe1 = Regex("""translator="([^"]+)"[^>]*data-fansub-name="([^"]*)""")
    private val trRe2 = Regex("""data-fansub-name="([^"]*)"[^>]*translator="([^"]+)""")
    private val vidRe1 = Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)""")
    private val vidRe2 = Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)""")
    private val cleanRe = Regex("""\s*[-–]\s*Anizm[.\w]*$""", RegexOption.IGNORE_CASE)
    private val adsRe = Regex("""\([Rr]eklamsız\)""")

    // Reusable empty response — avoid allocations in shouldInterceptRequest (called 100s of times)
    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun log(msg: String) { android.util.Log.d("Anizle", msg) }

    private suspend fun getSession() {
        val now = System.currentTimeMillis()
        if (csrfToken != null && (now - sessionFetchedAt) < sessionTtlMs) return
        try {
            var resp = app.get(mainUrl, headers = baseHeaders)
            var html = resp.text
            if (isCf(html)) { resp = app.get(mainUrl, headers = baseHeaders, interceptor = cfKiller); html = resp.text }
            csrfToken = csrfRe1.find(html)?.groupValues?.get(1)
                ?: csrfRe2.find(html)?.groupValues?.get(1)
            sessionFetchedAt = System.currentTimeMillis()
        } catch (_: Exception) {}
    }

    private fun isCf(html: String) = html.contains("Just a moment", true) || html.contains("cf-browser-verification", true)

    private val baseHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7", "Referer" to "$mainUrl/")
    private val xhrHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7", "Origin" to mainUrl, "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/javascript, */*; q=0.01")

    // ── Batch embed resolver ────────────────────────────────────────────────
    private suspend fun resolveEmbeds(numIds: List<String>, episodeUrl: String): Map<String, String> {
        if (numIds.isEmpty()) return emptyMap()
        val results = mutableMapOf<String, String>()

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
                // Stealth: CookieManager shares cookies between OkHttp/cfKiller and WebView
                // automatically within the same app process — no manual sync needed

                fun finish() {
                    if (!done) { done = true; try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}; cont.resume(results) }
                }

                val globalTimeout = Runnable { log("resolve: global timeout (${results.size}/${numIds.size})"); finish() }
                handler.postDelayed(globalTimeout, 35_000L)

                var currentTarget = ""; var currentIdx = -1
                val perIdTimeout = arrayOfNulls<Runnable>(1)
                var usedFallback = false

                fun resolveNext() {
                    perIdTimeout[0]?.let { handler.removeCallbacks(it) }
                    currentIdx++
                    if (currentIdx == 1 && results.isEmpty() && !usedFallback) {
                        log("resolve: first failed, falling back to loadUrl")
                        usedFallback = true; currentIdx = -1; pageReady = false
                        wv.loadUrl(episodeUrl)
                        return
                    }
                    if (currentIdx >= numIds.size || done) { handler.removeCallbacks(globalTimeout); finish(); return }
                    currentTarget = numIds[currentIdx]
                    val nid = numIds[currentIdx]
                    log("resolve: [${currentIdx}/${numIds.size}] numId=$nid")

                    perIdTimeout[0] = Runnable { if (currentTarget == nid && !done) { log("resolve: timeout $nid"); resolveNext() } }
                    handler.postDelayed(perIdTimeout[0]!!, 5_000L)

                    // Stealth: 200-600ms random delay between iframes (fast but still human-like)
                    val delay = if (currentIdx == 0) 0L else (200L + (Math.random() * 400).toLong())
                    handler.postDelayed({
                        if (done) return@postDelayed
                        wv.evaluateJavascript(
                            "(function(){var o=document.getElementById('_pf');if(o)o.remove();" +
                            "var f=document.createElement('iframe');f.id='_pf';" +
                            "f.style.cssText='width:1px;height:1px;position:absolute;left:-9999px';" +
                            "f.src='/player/$nid';document.body.appendChild(f);})()", null)
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
                        val host = request.url?.host ?: ""

                        // Fast path: skip processing for anizm.net sub-resources
                        // (CSS, JS, fonts etc.) — we only care about cross-domain embeds
                        if (host.contains("anizm")) {
                            // Only process /player/ paths
                            if (url.contains("/player/")) {
                                val tgt = currentTarget
                                if (tgt.isBlank() || !url.endsWith("/player/$tgt"))
                                    return emptyResponse()
                            }
                            return null // let all other anizm.net requests through
                        }

                        // Cross-domain: check for embeds we want to intercept
                        if (host.contains("player")) {
                            apRe.find(url)?.let { m ->
                                val domain = m.groupValues[1]
                                if (!playerBase.contains(domain.substringAfter("://"))) {
                                    playerBase = domain; log("resolve: player domain updated to $domain")
                                }
                                handler.post { wv.evaluateJavascript("_b.h('ap:${m.groupValues[2]}')", null) }
                                return emptyResponse()
                            }
                        }
                        if (host.contains("google")) {
                            gdRe.find(url)?.let { m ->
                                log("resolve: GDrive: ${m.groupValues[1]}")
                                handler.post { wv.evaluateJavascript("_b.h('gd:${m.groupValues[1]}')", null) }
                                return emptyResponse()
                            }
                        }

                        // Block ads/analytics
                        if (host.contains("statbest") || host.contains("adservice") || host.contains("doubleclick"))
                            return emptyResponse()
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

                log("resolve: starting ${numIds.size} resolves")
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
            app.get("$mainUrl/searchAnime", headers = xhrHeaders, params = params).text
        } catch (_: Exception) { return emptyList() }
        return try {
            val arr = JSONObject(responseText).optJSONArray("data") ?: JSONArray(responseText)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = obj.optString("info_slug", "").ifBlank { return@mapNotNull null }
                val title = obj.optString("info_title", "").ifBlank { return@mapNotNull null }
                val thumb = obj.optString("info_poster", "")
                val poster = if (thumb.isBlank()) null else if (thumb.startsWith("http")) thumb else "$mainUrl/storage/pcovers/$thumb"
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
        fun clean(raw: String) = raw.replace(cleanRe, "").trim()
        val items = doc.select("a.imgWrapperLink, div.posterBlock > a, a[href*=-bolum]")
            .ifEmpty { doc.select("a[href*=-izle]").filter { it.selectFirst("img") != null } }
            .distinctBy { it.attr("href") }.mapNotNull { el ->
                val href = el.attr("abs:href").ifBlank { return@mapNotNull null }; val isEp = href.contains("-bolum")
                val img = el.selectFirst("img")
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
    private fun absUrl(src: String): String? {
        if (src.isBlank() || src.startsWith("data:")) return null
        return when {
            src.startsWith("http") -> src; src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "$mainUrl$src"; else -> "$mainUrl/$src"
        }
    }

    private fun findImg(el: org.jsoup.nodes.Element): String? {
        return absUrl(el.attr("src")) ?: absUrl(el.attr("data-src"))
            ?: absUrl(el.attr("data-original")) ?: absUrl(el.attr("data-lazy-src"))
    }

    override suspend fun load(url: String): LoadResponse {
        getSession(); val doc = app.get(url, headers = baseHeaders).document

        val title = doc.selectFirst("h2.anizm_pageTitle, h2.page-title, h1, .anime-title")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ")

        // Poster: try multiple selectors + fallback to og:image
        val poster = sequenceOf(
            doc.selectFirst("div.infoPosterImg img"),
            doc.selectFirst("img[src*=pcovers]"),
            doc.selectFirst("img[data-src*=pcovers]"),
            doc.selectFirst(".posterBlock img, .poster img, .cover img"),
        ).filterNotNull().map { findImg(it) }.firstOrNull { it != null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { absUrl(it) }

        // Plot: try content selectors + fallback to meta description
        val plot = doc.selectFirst("div.infoDesc, .anime-description, .description, .synopsis")
            ?.also { it.select("h1,h2,h3,h4,a,script,style").remove() }?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }

        val year = doc.select("span.dataValue, .info-value, li, td").map { it.text().trim() }
            .firstOrNull { it.matches(Regex("""\d{4}""")) && it.toInt() in 1950..2030 }?.toIntOrNull()
        val tags = doc.select("span.dataValue > span.tag > span.label, a[href*=/kategoriler/], .genre a")
            .map { it.text().trim() }.filter { it.isNotBlank() && it.length < 30 }.take(6).ifEmpty { null }

        val allLinks = doc.select("div#episodesMiddle a[href]")
            .ifEmpty { doc.select("div.episodeListTabContent a[href]") }
            .ifEmpty { doc.select(".episode-list a[href], a[href*=-bolum]") }
            .distinctBy { it.attr("abs:href") }
        val episodes = allLinks.mapNotNull { el ->
            val href = el.attr("abs:href").ifBlank { return@mapNotNull null }
            val label = el.text().trim().ifBlank { return@mapNotNull null }
            val ll = label.lowercase()
            if (href.contains("fragman") || ll.contains("fragman")) return@mapNotNull null
            if (href.contains("-pv-") || ll == "pv" || ll.endsWith(" pv")) return@mapNotNull null
            newEpisode(href) { name = label }
        }.mapIndexed { i, ep -> ep.apply { episode = i + 1 } }
        log("load: '$title' poster=${poster != null} plot=${(plot?.length ?: 0) > 0} eps=${episodes.size}")
        return newAnimeLoadResponse(title, url, TvType.Anime) { posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags; addEpisodes(DubStatus.Subbed, episodes) }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        getSession(); log("loadLinks: $data")

        val epHtml = try { app.get(data, headers = baseHeaders).text } catch (e: Exception) { log("loadLinks: page error: ${e.message}"); return false }
        log("loadLinks: page len=${epHtml.length}")

        val translators = mutableListOf<Pair<String, String>>()
        trRe1.findAll(epHtml).forEach { m ->
            val u = m.groupValues[1]; if (u.isNotBlank() && translators.none { it.first == u }) translators += u to m.groupValues[2].ifBlank { "Fansub" }
        }
        if (translators.isEmpty()) trRe2.findAll(epHtml).forEach { m ->
            val u = m.groupValues[2]; if (u.isNotBlank() && translators.none { it.first == u }) translators += u to m.groupValues[1].ifBlank { "Fansub" }
        }
        log("loadLinks: ${translators.size} translators: ${translators.map { it.second }}")
        if (translators.isEmpty()) return false

        data class VidInfo(val numId: String, val name: String, val fansub: String)
        val allWanted = mutableListOf<VidInfo>()
        for ((trUrl, fansubName) in translators) {
            val trText = try { app.get(trUrl, headers = xhrHeaders + mapOf("Referer" to data)).text } catch (e: Exception) { log("loadLinks: tr error ($fansubName): ${e.message}"); continue }
            val trHtml = try { JSONObject(trText).optString("data", "") } catch (_: Exception) { "" }
            if (trHtml.isBlank()) continue
            val videos = mutableListOf<Pair<String, String>>()
            vidRe1.findAll(trHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
            if (videos.isEmpty()) vidRe2.findAll(trHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }
            log("loadLinks: $fansubName: ${videos.map { it.second }}")
            for ((videoUrl, videoName) in videos) {
                val vl = videoName.lowercase()
                if (!vl.contains("aincrad") && !vl.contains("gdrive") && !vl.contains("google") && !vl.contains("drive")) continue
                val numId = numIdRe.find(videoUrl)?.groupValues?.get(1) ?: continue
                allWanted.add(VidInfo(numId, videoName, fansubName))
            }
        }
        log("loadLinks: ${allWanted.size} wanted")
        if (allWanted.isEmpty()) return false

        val uniqueIds = allWanted.map { it.numId }.distinct()

        // Check cache first — skip WebView entirely if all cached
        val embedMap = mutableMapOf<String, String>()
        val uncachedIds = mutableListOf<String>()
        for (id in uniqueIds) {
            val cached = getCached(id)
            if (cached != null) { embedMap[id] = cached } else { uncachedIds.add(id) }
        }
        log("loadLinks: ${uniqueIds.size} unique, ${embedMap.size} cached, ${uncachedIds.size} to resolve")

        // Only spin up WebView for uncached numIds
        if (uncachedIds.isNotEmpty()) {
            val resolved = try { resolveEmbeds(uncachedIds, data) } catch (e: Exception) { log("loadLinks: resolve error: ${e.message}"); emptyMap() }
            for ((id, embed) in resolved) { embedMap[id] = embed; putCache(id, embed) }
        }
        log("loadLinks: total ${embedMap.size}/${uniqueIds.size}: $embedMap")

        var found = false
        for (vi in allWanted) {
            val embed = embedMap[vi.numId]
            if (embed == null) { log("step4: ${vi.fansub}/${vi.name} MISSING"); continue }
            val label = "${vi.fansub} - ${vi.name.replace(adsRe, "").trim()}"
            log("step4: $label embed=$embed")

            if (embed.startsWith("ap:")) {
                val hash = embed.removePrefix("ap:")
                val playerRef = "$playerBase/player/$hash"
                val aHeaders = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, */*; q=0.01", "Referer" to playerRef, "Origin" to playerBase)
                val streamText = try { app.post("$playerBase/player/index.php?data=$hash&do=getVideo", headers = aHeaders).text }
                    catch (e: Exception) { log("aincrad error: ${e.message}"); continue }
                val json = try { JSONObject(streamText) } catch (_: Exception) { continue }
                val securedLink = json.optString("securedLink", "")
                val videoSource = json.optString("videoSource", "")
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
