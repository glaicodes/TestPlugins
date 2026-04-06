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
            log("session: ok")
        } catch (e: Exception) { log("session error: ${e.message}") }
    }

    private fun isCf(html: String) = html.contains("Just a moment", true) || html.contains("cf-browser-verification", true)

    private val baseHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7", "Referer" to "$mainUrl/")
    private val xhrHeaders get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7", "Origin" to mainUrl, "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/javascript, */*; q=0.01")

    // ── All-in-WebView resolver ─────────────────────────────────────────────
    // Loads episode page in WebView, extracts translators + video lists via JS,
    // then resolves each numId by injecting iframes with random delays.
    // Returns: list of (fansubName, videoName, embedInfo) where embedInfo is
    // "ap:{hash}" for anizmplayer or "gd:{fileId}" for GDrive
    private data class ResolvedLink(val fansub: String, val videoName: String, val embed: String)

    private suspend fun resolveAll(episodeUrl: String): List<ResolvedLink> {
        val results = mutableListOf<ResolvedLink>()

        return suspendCoroutine { cont ->
            val handler = android.os.Handler(Looper.getMainLooper())
            handler.post {
                var done = false
                val ctx = try { com.lagradost.cloudstream3.AcraApplication.context } catch (_: Exception) { null }
                if (ctx == null) { cont.resume(emptyList()); return@post }

                val wv = WebView(ctx).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // Don't block images — stealth: looks like real browsing
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                fun finish() {
                    if (!done) { done = true; try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}; cont.resume(results) }
                }

                val globalTimeout = Runnable { log("resolve: global timeout (${results.size} found)"); finish() }
                handler.postDelayed(globalTimeout, 60_000L)

                // State for iframe resolution phase
                val apRe = Regex("""anizmplayer\.com/(?:video|player)/([a-f0-9]{32})""")
                val gdRe = Regex("""drive\.google\.com/(?:file/d/|uc\?[^"]*id=)([A-Za-z0-9_-]{25,})""")
                var currentTarget = ""
                var resolveIdx = -1
                // Populated after translator fetch phase
                data class WantedVideo(val numId: String, val name: String, val fansub: String)
                val wanted = mutableListOf<WantedVideo>()
                val hashMap = mutableMapOf<String, String>()
                var uniqueIds = listOf<String>()
                val perIdTimeout = arrayOfNulls<Runnable>(1)

                fun resolveNextId() {
                    perIdTimeout[0]?.let { handler.removeCallbacks(it) }
                    resolveIdx++
                    if (resolveIdx >= uniqueIds.size || done) {
                        // All resolved — build results
                        for (w in wanted) {
                            val embed = hashMap[w.numId] ?: continue
                            results.add(ResolvedLink(w.fansub, w.name, embed))
                        }
                        handler.removeCallbacks(globalTimeout)
                        finish()
                        return
                    }
                    currentTarget = uniqueIds[resolveIdx]
                    val nid = uniqueIds[resolveIdx]
                    log("resolve: [${resolveIdx}/${uniqueIds.size}] numId=$nid")

                    perIdTimeout[0] = Runnable {
                        if (currentTarget == nid && !done) { log("resolve: timeout for $nid"); resolveNextId() }
                    }
                    handler.postDelayed(perIdTimeout[0]!!, 10_000L)

                    // Random delay 500-1500ms between iframes — mimics human clicking
                    val delay = if (resolveIdx == 0) 0L else (500L + (Math.random() * 1000).toLong())
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

                // JS bridge
                wv.addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun onHash(v: String) {
                        val tgt = currentTarget
                        if (v.isNotBlank() && tgt.isNotBlank() && !hashMap.containsKey(tgt)) {
                            log("resolve: $v for numId=$tgt"); hashMap[tgt] = v
                        }
                        handler.post { resolveNextId() }
                    }
                    @android.webkit.JavascriptInterface
                    fun onVideos(json: String) {
                        // Called from JS after fetching all translator data
                        try {
                            val arr = JSONArray(json)
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                wanted.add(WantedVideo(obj.getString("numId"), obj.getString("name"), obj.getString("fansub")))
                            }
                        } catch (e: Exception) { log("onVideos parse error: ${e.message}") }
                        uniqueIds = wanted.map { it.numId }.distinct()
                        log("resolve: ${wanted.size} wanted, ${uniqueIds.size} unique numIds")
                        if (uniqueIds.isEmpty()) { handler.removeCallbacks(globalTimeout); finish(); return }
                        resolveNextId()
                    }
                    @android.webkit.JavascriptInterface
                    fun log(msg: String) { this@AnizleProvider.log("JS: $msg") }
                }, "_b")

                var pageReady = false

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        // Intercept anizmplayer.com → Aincrad hash
                        apRe.find(url)?.let { m ->
                            handler.post { wv.evaluateJavascript("_b.onHash('ap:${m.groupValues[1]}')", null) }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        // Intercept drive.google.com → GDrive file ID
                        gdRe.find(url)?.let { m ->
                            log("resolve: intercepted GDrive: ${m.groupValues[1]}")
                            handler.post { wv.evaluateJavascript("_b.onHash('gd:${m.groupValues[1]}')", null) }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        // Block ALL /player/ EXCEPT current target
                        if (url.contains("/player/")) {
                            val tgt = currentTarget
                            if (tgt.isBlank() || !url.endsWith("/player/$tgt"))
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        // Block analytics only — let everything else (CSS/JS/images) load naturally
                        val l = url.lowercase()
                        if (l.contains("statbest") || l.contains("analytics") || l.contains("adservice") || l.contains("doubleclick"))
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!pageReady && url?.contains("anizm.net") == true) {
                            pageReady = true
                            log("resolve: page loaded, extracting translators via JS")

                            // Phase 1: Extract translator URLs from the loaded page,
                            // fetch their video lists via JS fetch(), collect wanted videos,
                            // then call back to Kotlin with the list.
                            val extractJs = """
                            (async function(){
                                try {
                                    // Find translator buttons
                                    var trBtns = document.querySelectorAll('[translator]');
                                    var translators = [];
                                    trBtns.forEach(function(el){
                                        var url = el.getAttribute('translator');
                                        var name = el.getAttribute('data-fansub-name') || 'Fansub';
                                        if(url && !translators.find(function(t){return t.url===url}))
                                            translators.push({url:url, name:name});
                                    });
                                    _b.log('translators: ' + translators.length + ' ' + translators.map(function(t){return t.name}).join(','));
                                    if(!translators.length){ _b.onVideos('[]'); return; }

                                    var csrf = '';
                                    var meta = document.querySelector('meta[name="csrf-token"]');
                                    if(meta) csrf = meta.getAttribute('content') || '';

                                    var allWanted = [];
                                    for(var i=0; i<translators.length; i++){
                                        var tr = translators[i];
                                        try {
                                            var resp = await fetch(tr.url, {
                                                headers: {'X-Requested-With':'XMLHttpRequest',
                                                    'X-CSRF-TOKEN': csrf,
                                                    'Accept':'application/json, text/javascript, */*; q=0.01'},
                                                credentials:'include'
                                            });
                                            var text = await resp.text();
                                            var j = JSON.parse(text);
                                            var html = j.data || '';
                                            // Parse video buttons from HTML string
                                            var div = document.createElement('div');
                                            div.innerHTML = html;
                                            var vBtns = div.querySelectorAll('[video]');
                                            var names = [];
                                            vBtns.forEach(function(el){
                                                var vUrl = el.getAttribute('video');
                                                var vName = el.getAttribute('data-video-name') || 'Player';
                                                names.push(vName);
                                                var vl = vName.toLowerCase();
                                                if(vl.indexOf('aincrad')<0 && vl.indexOf('gdrive')<0 && vl.indexOf('google')<0 && vl.indexOf('drive')<0) return;
                                                var m = vUrl.match(/\/video\/(\d+)/);
                                                if(m) allWanted.push({numId:m[1], name:vName, fansub:tr.name});
                                            });
                                            _b.log(tr.name + ': ' + names.length + ' videos: ' + names.join(', '));
                                        } catch(e){ _b.log('tr fetch error ' + tr.name + ': ' + e); }
                                    }
                                    _b.log('total wanted: ' + allWanted.length);
                                    _b.onVideos(JSON.stringify(allWanted));
                                } catch(e){ _b.log('extract error: ' + e); _b.onVideos('[]'); }
                            })();
                            """.trimIndent()
                            view?.evaluateJavascript(extractJs, null)
                        }
                    }
                }

                // Single page load — the ONLY request to anizm.net
                wv.loadUrl(episodeUrl)
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

        val resolved = try { resolveAll(data) } catch (e: Exception) { log("loadLinks: resolve error: ${e.message}"); emptyList() }
        log("loadLinks: ${resolved.size} links resolved")

        var found = false
        for (r in resolved) {
            val label = "${r.fansub} - ${r.videoName.replace(Regex("""\([Rr]eklamsız\)"""), "").trim()}"
            log("step4: $label embed=${r.embed}")

            // ── Aincrad (ap:{hash}) ────────────────────────────────────
            if (r.embed.startsWith("ap:")) {
                val hash = r.embed.removePrefix("ap:")
                val playerRef = "$playerBase/player/$hash"
                val aHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, */*; q=0.01",
                    "Referer" to playerRef, "Origin" to playerBase)
                val streamText = try { app.post("$playerBase/player/index.php?data=$hash&do=getVideo", headers = aHeaders).text }
                    catch (e: Exception) { log("aincrad error: ${e.message}"); continue }
                val json = try { JSONObject(streamText) } catch (_: Exception) { continue }
                val securedLink = json.optString("securedLink", "")
                val videoSource = json.optString("videoSource", "")
                log("aincrad: hls=${json.optBoolean("hls")} secured=${securedLink.take(60)} source=${videoSource.take(60)}")

                if (json.optBoolean("hls", false) && securedLink.isNotBlank()) {
                    // Primary: securedLink with auth tokens (plays immediately)
                    callback(newExtractorLink(source = label, name = label, url = securedLink, type = ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value; referer = playerRef
                        headers = mapOf("Origin" to playerBase, "Referer" to playerRef)
                    })
                    found = true
                    // Secondary: videoSource without expiry tokens (better for download)
                    if (videoSource.isNotBlank() && videoSource != securedLink) {
                        val dlLabel = "$label (DL)"
                        callback(newExtractorLink(source = dlLabel, name = dlLabel, url = videoSource, type = ExtractorLinkType.M3U8) {
                            quality = Qualities.P1080.value; referer = playerRef
                            headers = mapOf("Origin" to playerBase, "Referer" to playerRef)
                        })
                    }
                } else if (videoSource.isNotBlank()) {
                    callback(newExtractorLink(source = label, name = label, url = videoSource, type = ExtractorLinkType.VIDEO) {
                        quality = Qualities.Unknown.value; referer = playerRef })
                    found = true
                }
                continue
            }

            // ── GDrive (gd:{fileId}) ───────────────────────────────────
            if (r.embed.startsWith("gd:")) {
                val fileId = r.embed.removePrefix("gd:")
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
