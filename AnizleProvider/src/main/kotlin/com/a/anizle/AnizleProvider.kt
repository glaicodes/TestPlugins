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

    private val playerBase = "https://anizmplayer.com"
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
    // Uses loadDataWithBaseURL to establish anizm.net context instantly (no
    // network request), then injects iframes for each numId with random delays.
    // If CF rejects iframes from this context, fall back to wv.loadUrl.
    // Returns: numId -> "ap:{hash}" | "gd:{fileId}"
    private suspend fun resolveEmbeds(numIds: List<String>, episodeUrl: String): Map<String, String> {
        if (numIds.isEmpty()) return emptyMap()
        val results = mutableMapOf<String, String>()
        val apRe = Regex("""anizmplayer\.com/(?:video|player)/([a-f0-9]{32})""")
        val gdRe = Regex("""drive\.google\.com/(?:file/d/|uc\?[^"]*id=)([A-Za-z0-9_-]{25,})""")

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

                    // If first numId timed out, try fallback (loadUrl instead of loadData)
                    if (currentIdx == 1 && results.isEmpty() && !usedFallback) {
                        log("resolve: first numId failed, falling back to loadUrl")
                        usedFallback = true
                        currentIdx = -1
                        wv.loadUrl(episodeUrl)
                        return // onPageFinished will call resolveNext again
                    }

                    if (currentIdx >= numIds.size || done) { handler.removeCallbacks(globalTimeout); finish(); return }
                    currentTarget = numIds[currentIdx]
                    val nid = numIds[currentIdx]
                    log("resolve: [${currentIdx}/${numIds.size}] numId=$nid")

                    perIdTimeout[0] = Runnable { if (currentTarget == nid && !done) { log("resolve: timeout $nid"); resolveNext() } }
                    handler.postDelayed(perIdTimeout[0]!!, 8_000L)

                    // Stealth: random delay 500-1500ms between iframes
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
                        apRe.find(url)?.let { m ->
                            handler.post { wv.evaluateJavascript("_b.h('ap:${m.groupValues[1]}')", null) }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        gdRe.find(url)?.let { m ->
                            log("resolve: GDrive: ${m.groupValues[1]}")
                            handler.post { wv.evaluateJavascript("_b.h('gd:${m.groupValues[1]}')", null) }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
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
                            if (usedFallback && url?.contains("anizm.net") == true) {
                                log("resolve: fallback page loaded, restarting resolves")
                                resolveNext()
                            }
                        }
                    }
                }

                // Fast path: loadDataWithBaseURL establishes anizm.net context
                // instantly without any network request. No double page load.
                log("resolve: loading blank with base=$episodeUrl")
                wv.loadDataWithBaseURL(
                    episodeUrl,
                    "<html><body></body></html>",
                    "text/html", "utf-8", null
                )
                // Start resolving immediately — no need to wait for onPageFinished
                // since loadDataWithBaseURL is synchronous
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
        fun clean(raw: String) = raw.replace(Regex("""\s*-\s*Anizm[.\w]*$""", RegexOption.IGNORE_CASE), "").trim()
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
    override suspend fun load(url: String): LoadResponse {
        getSession(); val doc = app.get(url, headers = baseHeaders).document
        val title = doc.selectFirst("h2.anizm_pageTitle, h1")?.text()?.trim() ?: url.substringAfterLast("/").replace("-", " ")
        val posterEl = doc.selectFirst("div.infoPosterImg > img, img[src*=pcovers]")
        val poster = posterEl?.attr("abs:src")?.ifBlank { posterEl.attr("data-src") }?.ifBlank { null }
        val plot = doc.selectFirst("div.infoDesc, .anime-description, .description")?.also { it.select("h1,h2,h3,h4,a").remove() }?.text()?.trim()?.ifBlank { null }
        val year = doc.select("span.dataValue, li, td").map { it.text().trim() }.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        val tags = doc.select("span.dataValue > span.tag > span.label, a[href*=/kategoriler/]").map { it.text().trim() }.filter { it.isNotBlank() }.take(4).ifEmpty { null }
        val allLinks = doc.select("div#episodesMiddle a[href]").ifEmpty { doc.select("div.episodeListTabContent a[href]") }.distinctBy { it.attr("abs:href") }
        val episodes = allLinks.mapNotNull { el ->
            val href = el.attr("abs:href").ifBlank { return@mapNotNull null }; val label = el.text().trim().ifBlank { return@mapNotNull null }
            val ll = label.lowercase()
            if (href.contains("fragman") || ll.contains("fragman")) return@mapNotNull null
            if (href.contains("-pv-") || ll == "pv" || ll.endsWith(" pv")) return@mapNotNull null
            newEpisode(href) { name = label }
        }.mapIndexed { i, ep -> ep.apply { episode = i + 1 } }
        return newAnimeLoadResponse(title, url, TvType.Anime) { posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags; addEpisodes(DubStatus.Subbed, episodes) }
    }

    // ── Load links ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        getSession(); log("loadLinks: $data")

        val epHtml = try { app.get(data, headers = baseHeaders).text } catch (e: Exception) { log("loadLinks: page error: ${e.message}"); return false }
        log("loadLinks: page len=${epHtml.length}")

        val translators = mutableListOf<Pair<String, String>>()
        Regex("""translator="([^"]+)"[^>]*data-fansub-name="([^"]*)""").findAll(epHtml).forEach { m ->
            val u = m.groupValues[1]; if (u.isNotBlank() && translators.none { it.first == u }) translators += u to m.groupValues[2].ifBlank { "Fansub" }
        }
        if (translators.isEmpty()) Regex("""data-fansub-name="([^"]*)"[^>]*translator="([^"]+)""").findAll(epHtml).forEach { m ->
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
            Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)""").findAll(trHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
            if (videos.isEmpty()) Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)""").findAll(trHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }
            log("loadLinks: $fansubName: ${videos.map { it.second }}")
            for ((videoUrl, videoName) in videos) {
                val vl = videoName.lowercase()
                if (!vl.contains("aincrad") && !vl.contains("gdrive") && !vl.contains("google") && !vl.contains("drive")) continue
                val numId = Regex("""/video/(\d+)""").find(videoUrl)?.groupValues?.get(1) ?: continue
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
