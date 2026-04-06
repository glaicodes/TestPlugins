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

    private suspend fun getSession() {
        val now = System.currentTimeMillis()
        if (csrfToken != null && (now - sessionFetchedAt) < sessionTtlMs) return
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
        } catch (_: Exception) {}
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

    private fun log(msg: String) {
        android.util.Log.d("Anizle", msg)
    }

    // Loads episode page ONCE, resolves each numId by injecting iframes sequentially.
    // Blocks the page's default player iframe so each numId gets its own hash.
    private suspend fun resolveHashes(
        numIds: List<String>,
        episodeUrl: String
    ): Map<String, String> {
        if (numIds.isEmpty()) return emptyMap()
        val results = mutableMapOf<String, String>()
        val hp = Regex("""anizmplayer\.com/(?:video|player)/([a-f0-9]{32})""")

        return suspendCoroutine { cont ->
            val handler = android.os.Handler(Looper.getMainLooper())
            handler.post {
                var done = false
                val ctx = try { com.lagradost.cloudstream3.AcraApplication.context }
                    catch (_: Exception) { null }
                if (ctx == null) { cont.resume(emptyMap()); return@post }

                val wv = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.blockNetworkImage = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                fun finish() {
                    if (!done) {
                        done = true
                        try { wv.stopLoading() } catch (_: Exception) {}
                        try { wv.destroy() } catch (_: Exception) {}
                        cont.resume(results)
                    }
                }

                val globalTimeout = Runnable { log("resolve: global timeout"); finish() }
                handler.postDelayed(globalTimeout, 55_000L)

                var currentTarget = ""
                var currentIdx = -1
                val perIdTimeout = arrayOfNulls<Runnable>(1)

                fun resolveNext() {
                    perIdTimeout[0]?.let { handler.removeCallbacks(it) }
                    currentIdx++
                    if (currentIdx >= numIds.size || done) {
                        handler.removeCallbacks(globalTimeout)
                        finish()
                        return
                    }
                    currentTarget = numIds[currentIdx]
                    val nid = numIds[currentIdx]
                    log("resolve: [$currentIdx/${numIds.size}] numId=$nid")

                    val r = Runnable {
                        if (currentTarget == nid && !done) {
                            log("resolve: timeout for $nid")
                            resolveNext()
                        }
                    }
                    perIdTimeout[0] = r
                    handler.postDelayed(r, 10_000L)

                    val js = """
                    (function(){
                        var old=document.getElementById('_pf');
                        if(old)old.remove();
                        var f=document.createElement('iframe');
                        f.id='_pf';
                        f.style.cssText='width:1px;height:1px;position:absolute;left:-9999px';
                        f.src='/player/$nid';
                        document.body.appendChild(f);
                    })();
                    """.trimIndent()
                    wv.evaluateJavascript(js, null)
                }

                wv.addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun h(v: String) {
                        val tgt = currentTarget
                        if (v.isNotBlank() && tgt.isNotBlank()) {
                            log("resolve: hash=$v for numId=$tgt")
                            results[tgt] = v
                        }
                        handler.post { resolveNext() }
                    }
                }, "_b")

                var pageReady = false

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?, request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null

                        val m = hp.find(url)
                        if (m != null) {
                            val hash = m.groupValues[1]
                            handler.post { wv.evaluateJavascript("_b.h('$hash')", null) }
                            return WebResourceResponse("text/plain", "utf-8",
                                ByteArrayInputStream(ByteArray(0)))
                        }

                        // Block ALL /player/ EXCEPT current target
                        if (url.contains("/player/")) {
                            val tgt = currentTarget
                            if (tgt.isBlank() || !url.endsWith("/player/$tgt")) {
                                return WebResourceResponse("text/plain", "utf-8",
                                    ByteArrayInputStream(ByteArray(0)))
                            }
                        }

                        // Block heavy resources but allow CSS/JS for stealth
                        val l = url.lowercase()
                        if (l.contains("statbest") || l.contains("analytics") ||
                            l.endsWith(".png") || l.endsWith(".jpg") ||
                            l.endsWith(".gif") || l.endsWith(".webp") ||
                            l.endsWith(".svg") || l.endsWith(".woff2") ||
                            l.endsWith(".woff") || l.endsWith(".ttf")) {
                            return WebResourceResponse("text/plain", "utf-8",
                                ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!pageReady && url?.contains("anizm.net") == true) {
                            pageReady = true
                            log("resolve: page ready, starting ${numIds.size} resolves")
                            resolveNext()
                        }
                    }
                }

                wv.loadUrl(episodeUrl)
            }
        }
    }

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
        } catch (_: Exception) { return emptyList() }

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
        } catch (_: Exception) { emptyList() }
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

        val epHtml = try { app.get(data, headers = baseHeaders).text }
            catch (_: Exception) { return false }

        // Step 1: all translators
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
        if (translators.isEmpty()) return false

        // Step 2: collect ALL wanted videos across ALL translators first
        data class VidInfo(val numId: String, val name: String, val fansubName: String)
        val allWanted = mutableListOf<VidInfo>()

        for ((trUrl, fansubName) in translators) {
            val trText = try { app.get(trUrl, headers = xhrHeaders + mapOf("Referer" to data)).text }
                catch (_: Exception) { continue }
            val trHtml = try { JSONObject(trText).optString("data", "") } catch (_: Exception) { "" }
            if (trHtml.isBlank()) continue

            val videos = mutableListOf<Pair<String, String>>()
            Regex("""video="([^"]+)"[^>]*data-video-name="([^"]*)"""")
                .findAll(trHtml).forEach { m -> videos += m.groupValues[1] to m.groupValues[2].ifBlank { "Player" } }
            if (videos.isEmpty())
                Regex("""data-video-name="([^"]*)"[^>]*video="([^"]+)"""")
                    .findAll(trHtml).forEach { m -> videos += m.groupValues[2] to m.groupValues[1].ifBlank { "Player" } }

            for ((videoUrl, videoName) in videos) {
                val vl = videoName.lowercase()
                if (!vl.contains("aincrad") && !vl.contains("gdrive") && !vl.contains("google")) continue
                val numId = Regex("""/video/(\d+)""").find(videoUrl)?.groupValues?.get(1) ?: continue
                allWanted.add(VidInfo(numId, videoName, fansubName))
            }
        }

        if (allWanted.isEmpty()) return false

        // Step 3: batch resolve ALL unique numIds in ONE WebView
        val uniqueIds = allWanted.map { it.numId }.distinct()
        log("loadLinks: ${allWanted.size} wanted videos, ${uniqueIds.size} unique numIds")
        val hashMap = try { resolveHashes(uniqueIds, data) } catch (_: Exception) { emptyMap() }
        log("loadLinks: resolved ${hashMap.size}/${uniqueIds.size} hashes")

        // Step 4: process each video
        var found = false
        for (vi in allWanted) {
            val hash = hashMap[vi.numId] ?: continue
            val vl = vi.name.lowercase()
            val label = "${vi.fansubName} - ${vi.name.replace(Regex("""\([Rr]eklamsız\)"""), "").trim()}"
            val playerRef = "$playerBase/player/$hash"

            // ── Aincrad ────────────────────────────────────────────────
            if (vl.contains("aincrad")) {
                val aHeaders = mapOf(
                    "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept"           to "application/json, */*; q=0.01",
                    "Referer"          to playerRef,
                    "Origin"           to playerBase,
                )
                val streamText = try {
                    app.post("$playerBase/player/index.php?data=$hash&do=getVideo", headers = aHeaders).text
                } catch (_: Exception) { continue }

                val json = try { JSONObject(streamText) } catch (_: Exception) { continue }
                val securedLink = json.optString("securedLink", "")
                val videoSource = json.optString("videoSource", "")

                if (json.optBoolean("hls", false) && securedLink.isNotBlank()) {
                    callback(newExtractorLink(
                        source = label, name = label, url = securedLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        referer = playerRef
                        headers = mapOf("Origin" to playerBase, "Referer" to playerRef)
                    })
                    found = true
                } else if (videoSource.isNotBlank()) {
                    callback(newExtractorLink(
                        source = label, name = label, url = videoSource,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        quality = Qualities.Unknown.value
                        referer = playerRef
                    })
                    found = true
                }
                continue
            }

            // ── GDrive ─────────────────────────────────────────────────
            val pageHtml = try {
                app.get(playerRef, headers = baseHeaders + mapOf("Referer" to "$mainUrl/")).text
            } catch (e: Exception) {
                log("gdrive page error: ${e.message}")
                continue
            }
            log("gdrive page len=${pageHtml.length} hash=$hash")
            log("gdrive page start: ${pageHtml.take(300)}")

            // Try multiple patterns for Google Drive
            val fileId = Regex("""[?&]id=([A-Za-z0-9_-]{25,})""").find(pageHtml)?.groupValues?.get(1)
                ?: Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{25,})""").find(pageHtml)?.groupValues?.get(1)
                ?: Regex("""drive\.google\.com/uc\?.*?id=([A-Za-z0-9_-]{25,})""").find(pageHtml)?.groupValues?.get(1)

            if (fileId != null) {
                log("gdrive fileId=$fileId")
                callback(newExtractorLink(
                    source = label, name = label,
                    url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                    type = ExtractorLinkType.VIDEO
                ) {
                    quality = Qualities.Unknown.value
                    referer = "https://drive.google.com/"
                })
                found = true
            } else {
                // Fallback: try iframe embed or direct link
                val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(pageHtml)?.groupValues?.get(1)
                    ?: Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(pageHtml)?.groupValues?.get(1)
                log("gdrive fallback embedUrl=$embedUrl")
                if (embedUrl != null) {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }
        return found
    }
}
