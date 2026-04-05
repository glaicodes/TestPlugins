package com.a.anizle

import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Video link chain:
 *  Step 1: Episode HTML → translator="URL" attrs
 *  Step 2: GET translator URL (XHR) → JSON {data: html} → video="URL" buttons
 *  Step 3: numId from video URL → WebView JS fetch() bypasses CF:
 *          fetch(/video/{numId}) → JSON {player} → fetch(/player/{numId}) → hash
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

    // ── WebView JS fetch resolver ──────────────────────────────────────────
    // CF blocks /player/{numId} from OkHttp AND from WebView page loads.
    // But fetch() inside a WebView uses the browser's real HTTP stack
    // (correct TLS fingerprint) with same-origin cookies from cfKiller.
    // We load a blank page with anizm.net origin, then use JS fetch()
    // to call /video/{numId} → /player/{numId} → extract hash.
    private suspend fun resolvePlayerHash(
        numId: String,
        episodeUrl: String,
        timeoutMs: Long = 20_000
    ): String? {
        android.util.Log.d("Anizle", "JSFetch: resolving numId=$numId")

        return suspendCoroutine { cont ->
            val handler = android.os.Handler(Looper.getMainLooper())
            handler.post {
                var resumed = false
                val ctx = try {
                    com.lagradost.cloudstream3.AcraApplication.context
                } catch (_: Exception) { null }
                if (ctx == null) {
                    android.util.Log.e("Anizle", "JSFetch: no app context")
                    cont.resume(null); return@post
                }

                val webView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                fun finish(hash: String?) {
                    if (!resumed) {
                        resumed = true
                        try { webView.stopLoading() } catch (_: Exception) {}
                        try { webView.destroy() } catch (_: Exception) {}
                        cont.resume(hash)
                    }
                }

                val timeoutRunnable = Runnable {
                    android.util.Log.w("Anizle", "JSFetch: timeout")
                    finish(null)
                }
                handler.postDelayed(timeoutRunnable, timeoutMs)

                // JS bridge for callback from fetch
                class JsBridge {
                    @android.webkit.JavascriptInterface
                    fun onResult(hash: String) {
                        android.util.Log.d("Anizle", "JSFetch: onResult hash='$hash'")
                        handler.removeCallbacks(timeoutRunnable)
                        handler.post { finish(hash.ifBlank { null }) }
                    }
                }
                webView.addJavascriptInterface(JsBridge(), "AnizleBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        android.util.Log.d("Anizle", "JSFetch: pageFinished $url")

                        // Now use fetch() through the WebView's browser HTTP stack.
                        // Step A: GET /video/{numId} XHR → JSON {player: "<iframe src=...>"}
                        // Step B: extract numId from iframe src → GET /player/{numId}
                        // Step C: extract anizmplayer hash from HTML
                        val js = """
                        (function(){
                            var numId = '$numId';
                            fetch('/video/' + numId, {
                                headers: {
                                    'X-Requested-With': 'XMLHttpRequest',
                                    'Accept': 'application/json, text/javascript, */*; q=0.01'
                                },
                                credentials: 'include'
                            })
                            .then(function(r){ return r.text(); })
                            .then(function(text){
                                console.log('AnizleStep1: len=' + text.length);
                                try {
                                    var j = JSON.parse(text);
                                    var player = j.player || '';
                                    var m = player.match(/player\/(\d+)/);
                                    if(m) return m[1];
                                } catch(e){}
                                // If JSON parse fails, it might be CF page
                                // Try /player/ directly
                                return numId;
                            })
                            .then(function(pid){
                                console.log('AnizleStep2: fetching /player/' + pid);
                                return fetch('/player/' + pid, {credentials: 'include'});
                            })
                            .then(function(r){ return r.text(); })
                            .then(function(html){
                                console.log('AnizleStep3: playerPage len=' + html.length);
                                var m = html.match(/anizmplayer\.com\/(?:video|player)\/([a-f0-9]{32})/);
                                AnizleBridge.onResult(m ? m[1] : '');
                            })
                            .catch(function(e){
                                console.log('AnizleFetchErr: ' + e);
                                AnizleBridge.onResult('');
                            });
                        })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }
                }

                // Load blank page with anizm.net origin — no network request,
                // but establishes same-origin for fetch() + shares cfKiller cookies
                webView.loadDataWithBaseURL(
                    "$mainUrl/blank",
                    "<html><body></body></html>",
                    "text/html", "utf-8", null
                )
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

            // Collect all numIds for this translator, resolve ONE via WebView
            // (WebView is slow, so resolve per-translator not per-video)
            val numIdToVideos = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for ((videoUrl, videoName) in videos) {
                val numId = Regex("""/video/(\d+)""").find(videoUrl)?.groupValues?.get(1) ?: continue
                numIdToVideos.getOrPut(numId) { mutableListOf() }.add(videoName to videoUrl)
            }

            for ((numId, videoList) in numIdToVideos) {
                android.util.Log.d("Anizle", "Step3: resolving numId=$numId (${videoList.size} videos)")

                // ── Step 3: get hash via WebView JS fetch ─────────────────
                val hash = try { resolvePlayerHash(numId, data) } catch (e: Exception) {
                    android.util.Log.e("Anizle", "Step3 WebView error: ${e.message}"); null
                }
                if (hash == null) {
                    android.util.Log.w("Anizle", "Step3: no hash for numId=$numId"); continue
                }
                android.util.Log.d("Anizle", "Step3: hash=$hash")

                // Process each video source that shares this numId
                for ((videoName, _) in videoList) {
                    val vl = videoName.lowercase()
                    val isAincrad = vl.contains("aincrad")
                    val isGdrive  = vl.contains("gdrive") || vl.contains("google")
                    val isKnown = isAincrad || isGdrive ||
                        vl.contains("voe") || vl.contains("filemoon") || vl.contains("uqload") ||
                        vl.contains("vidmoly") || vl.contains("sibnet") || vl.contains("sendvid") ||
                        vl.contains("doodstream") || vl.contains("ok.ru") ||
                        vl.contains("odnoklassniki") || vl.contains("sistenn") ||
                        vl.contains("liiivideo") || vl.contains("liivideo")
                    if (!isKnown) { android.util.Log.d("Anizle", "Skip unknown: $videoName"); continue }

                    val label = "$fansubName - ${videoName.replace(Regex("""\([Rr]eklamsız\)"""), "").trim()}"
                    val playerRef = "$playerBase/player/$hash"

                    // ── Step 4a: Aincrad ──────────────────────────────────
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

                    // ── Step 4b/c: fetch anizmplayer.com/player/{hash} (no CF) ─
                    val pageHtml = try {
                        app.get(playerRef, headers = baseHeaders + mapOf("Referer" to "$mainUrl/")).text
                    } catch (e: Exception) { android.util.Log.e("Anizle", "Step4 page: ${e.message}"); continue }
                    android.util.Log.d("Anizle", "Step4 page len=${pageHtml.length}")

                    // ── Step 4b: GDrive ──────────────────────────────────
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

                    // ── Step 4c: other hosts ─────────────────────────────
                    val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(pageHtml)?.groupValues?.get(1)
                        ?: Regex("""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                            .find(pageHtml)?.groupValues?.get(1)
                    android.util.Log.d("Anizle", "Step4c: $videoName embedUrl=$embedUrl")
                    if (embedUrl != null) { loadExtractor(embedUrl, mainUrl, subtitleCallback, callback); found = true }
                }
            }
        }
        return found
    }
}
