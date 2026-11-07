package seriesflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CloudStream 3 provider for SeriesFlix (seriesflixhd.boats).
 *
 * Site: WordPress series site.
 *  - Catalogs: /series-online/ and /genero/{slug}/ with /page/N/ pagination.
 *  - Search: /?s={query} (WordPress standard), paginated with /page/N/?s={query}.
 *  - Series: /serie/{slug}/ ; seasons: /temporada/{slug}-{n}/ ; episodes: /episodio/{slug-sxe}/.
 *  - Episode players: each language (LATINO / CASTELLANO / SUBTITULADO) is a dropdown
 *    whose options carry a Base64 `data-url` pointing to nupload.my/watch/{id}.
 *  - nupload player: the m3u8 URL is built from a Base64 char array (digits - constant)
 *    plus the session token `sesz`; the playlist requires Referer https://nupload.my/.
 */
class SeriesFlixProvider : MainAPI() {
    override var mainUrl = "https://seriesflixhd.boats"
    override var name = "SeriesFlix"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    companion object {
        private val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )

        private const val NUPLOAD_REFERER = "https://nupload.my/"

        /** Episode URL ends in ...-{season}x{episode}/ */
        private val episodeCodeRegex = Regex("""-(\d+)x(\d+)/?$""")

        /**
         * ARRAY.forEach(function NAME(value) { ACC += String.fromCharCode(
         *   parseInt(atob(value).replace(/\D/g,'')) - CONST); ...  -> sesz="TOKEN"
         * The array name, accumulator, constant and token all change per page load.
         */
        private val nuploadForEachRegex = Regex(
            """(\w+)\.forEach\(function\s*\w*\s*\(value\)\s*\{\s*(\w+)\s*\+=\s*""" +
                """String\.fromCharCode\(parseInt\(atob\(value\)\.replace\(/\\D/g,\s*''\s*\)\)\s*-\s*(\d+)\)"""
        )

        private val nuploadSeszRegex = Regex("""sesz="([^"]+)"""")

        /** Decodificador Base64 puro (sin APIs de Android, para el JAR multiplataforma). */
        private fun decodeBase64(input: String): ByteArray? {
            return try {
                val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                val clean = input.trim().trimEnd('=')
                val out = mutableListOf<Byte>()
                var buffer = 0
                var bits = 0
                for (c in clean) {
                    val v = table.indexOf(c)
                    if (v < 0) return null
                    buffer = (buffer shl 6) or v
                    bits += 6
                    if (bits >= 8) {
                        bits -= 8
                        out.add(((buffer shr bits) and 0xFF).toByte())
                    }
                }
                out.toByteArray()
            } catch (e: Exception) {
                null
            }
        }
    }

    override val mainPage = mainPageOf(
        "series-online" to "Series",
        "genero/accion" to "Acción",
        "genero/animacion" to "Animación",
        "genero/aventura" to "Aventura",
        "genero/ciencia-ficcion" to "Ciencia ficción",
        "genero/comedia" to "Comedia",
        "genero/crimen" to "Crimen",
        "genero/drama" to "Drama",
        "genero/romance" to "Romance",
        "genero/suspense" to "Suspense",
        "genero/terror" to "Terror",
        "genero/netflix" to "Netflix",
        "genero/amazon" to "Amazon",
        "genero/disney" to "Disney",
        "genero/hbo" to "HBO",
        "genero/fox" to "Fox",
        "genero/marvel" to "Marvel",
        "genero/dc" to "DC"
    )

    private suspend fun document(url: String): Document {
        val response = app.get(url, headers = baseHeaders)
        if (response.code == 403 || response.code == 503 ||
            response.text.contains("<title>Just a moment", ignoreCase = true)
        ) {
            throw ErrorLoadingException(
                "$name está bloqueando el acceso con Cloudflare. Prueba de nuevo más tarde."
            )
        }
        if (response.code !in 200..299) {
            throw ErrorLoadingException("$name respondió HTTP ${response.code}.")
        }
        return response.document
    }

    private fun listingUrl(data: String, page: Int): String =
        if (page <= 1) "$mainUrl/$data/" else "$mainUrl/$data/page/$page/"

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }

    /** Parses series cards (article.TPost) from listings and search results. */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (card in doc.select("article.TPost.B")) {
            val href = card.selectFirst("a[href]")?.let { a ->
                a.absUrl("href").ifBlank { a.attr("href") }
            }?.let { fixUrl(it) } ?: continue
            if (!href.contains("/serie/")) continue
            val title = card.selectFirst("h2.Title")?.text()?.trim() ?: continue
            val poster = card.selectFirst("img")?.let { img ->
                listOf("data-src", "src").firstNotNullOfOrNull { img.attr(it).ifBlank { null } }
            }?.let { fixUrl(it) }
            val year = card.selectFirst("span.Qlty.Yr")?.text()?.trim()?.take(4)?.toIntOrNull()
            results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            })
        }
        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = document(listingUrl(request.data, page))
        val items = parseCards(doc)
        val hasNext = items.isNotEmpty() &&
            doc.select("a[href]").any { it.attr("href").contains("/page/${page + 1}") }
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/$page/?s=$q"
        val doc = document(url)
        val items = parseCards(doc)
        return items to items.isNotEmpty()
    }

    override suspend fun search(query: String): List<SearchResponse> =
        searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    /** Parses episode rows (tr.Viewed) from a season page. */
    private fun parseEpisodes(html: String, fallbackSeason: Int? = null): List<Episode> {
        val doc = Jsoup.parse(html, mainUrl)
        val episodes = mutableListOf<Episode>()
        for (row in doc.select("tr.Viewed, .episode-list tr")) {
            val link = row.selectFirst("td.MvTbTtl a, a[href*='/episodio/']") ?: continue
            val href = link.absUrl("href").ifBlank { link.attr("href") }.let { fixUrl(it) } ?: continue
            if (!href.contains("/episodio/")) continue
            val title = link.text().trim().ifBlank { href.substringAfterLast('/').removeSuffix("/") }
            val code = episodeCodeRegex.find(href.removeSuffix("/"))
            val season = code?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallbackSeason
            val episode = code?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: row.selectFirst("span.Num")?.text()?.trim()?.toIntOrNull()
            val thumb = row.selectFirst("img")?.let { img ->
                listOf("data-src", "src").firstNotNullOfOrNull { img.attr(it).ifBlank { null } }
            }?.let { fixUrl(it) }
            episodes.add(newEpisode(href) {
                this.name = title
                this.season = season
                this.episode = episode
                this.posterUrl = thumb
            })
        }
        return episodes.distinctBy { it.data }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = document(url)
        val article = doc.selectFirst("article.TPost.A")
        val title = doc.selectFirst("h1.Title")?.text()?.trim()
            ?.removePrefix("Serie ") ?: return null
        val poster = article?.selectFirst("img.TPostBg")?.attr("src")?.let { fixUrl(it) }
        val plot = article?.selectFirst(".Description")?.text()?.trim()
        val year = article?.selectFirst("span.Date")?.text()?.trim()?.take(4)?.toIntOrNull()
        val genres = (article ?: doc).select("a[href*='/genero/']")
            .map { it.text().trim().trim(',').trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val seasonLinks = doc.select("main a[href*='/temporada/']")
            .map { it.absUrl("href").ifBlank { it.attr("href") } }
            .mapNotNull { fixUrl(it) }
            .filter { it.startsWith("http") }
            .distinct()

        val episodes = mutableListOf<Episode>()
        if (seasonLinks.isEmpty()) {
            episodes.addAll(parseEpisodes(doc.html()))
        } else {
            for (link in seasonLinks) {
                val season = Regex("""-(\d+)/?$""").find(link.removeSuffix("/"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                try {
                    episodes.addAll(parseEpisodes(document(link).html(), season))
                } catch (e: Exception) {
                    // Si una temporada falla, se continúa con las demás
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    /**
     * Resolves a nupload.my/watch/{id} page into its HLS playlist.
     * The page stores the stream URL as an array of Base64 tokens whose digits
     * (minus a per-load constant) map to characters, plus a session token sesz.
     */
    private suspend fun resolveNupload(
        watchUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(watchUrl, headers = baseHeaders, referer = "$mainUrl/")
            if (response.code !in 200..299) return false
            val html = response.text

            val forEach = nuploadForEachRegex.find(html) ?: return false
            val arrName = forEach.groupValues[1]
            val const = forEach.groupValues[3].toLongOrNull() ?: return false
            val sesz = nuploadSeszRegex.find(html)?.groupValues?.getOrNull(1)
                ?: return false

            val arrMatch = Regex("""var\s+$arrName\s*=\s*\[(.*?)]""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(html) ?: return false
            val tokens = Regex("\"([^\"]+)\"").findAll(arrMatch.groupValues[1]).toList()
            if (tokens.isEmpty()) return false

            val streamBase = buildString {
                for (tok in tokens) {
                    try {
                        val decoded = String(decodeBase64(tok.groupValues[1]) ?: return false)
                        val digits = decoded.filter(Char::isDigit)
                        if (digits.isEmpty()) return false
                        val code = digits.toLong() - const
                        if (code < 0 || code > Char.MAX_VALUE.code) return false
                        append(code.toInt().toChar())
                    } catch (e: Exception) {
                        return false
                    }
                }
            }.trim()
            if (!streamBase.startsWith("http")) return false

            callback(
                newExtractorLink("$name $label", "$name · $label", "$streamBase?s=$sesz") {
                    this.referer = NUPLOAD_REFERER
                    this.quality = Qualities.P720.value
                    this.type = ExtractorLinkType.M3U8
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = document(data)
        val html = doc.html()

        data class Option(val lang: String, val index: Int, val watchUrl: String)

        val options = mutableListOf<Option>()
        val seen = mutableSetOf<String>()
        val dropdowns = doc.select("div.optns-bx div.drpdn").ifEmpty { doc.select("div.drpdn") }
        if (dropdowns.isNotEmpty()) {
            for (dd in dropdowns) {
                val lang = dd.selectFirst("button.bstd > span")?.ownText()?.trim()
                    ?.ifBlank { null } ?: "Opción"
                val opts = dd.select("div.Button.sgty")
                opts.forEachIndexed { i, opt ->
                    val b64 = opt.attr("data-url").trim()
                    if (b64.isEmpty()) return@forEachIndexed
                    val watch = decodeBase64(b64)?.let { String(it) } ?: return@forEachIndexed
                    if (watch.startsWith("http") && seen.add(watch)) {
                        options.add(Option(lang, i + 1, watch))
                    }
                }
            }
        } else {
            doc.select("div.Button.sgty").forEachIndexed { i, opt ->
                val b64 = opt.attr("data-url").trim()
                if (b64.isEmpty()) return@forEachIndexed
                val watch = decodeBase64(b64)?.let { String(it) } ?: return@forEachIndexed
                if (watch.startsWith("http") && seen.add(watch)) {
                    options.add(Option("Opción", i + 1, watch))
                }
            }
        }
        if (options.isEmpty() && html.contains("data-url")) return false

        var any = false
        for (opt in options) {
            val label = if (opt.index > 1) "${opt.lang} ${opt.index}" else opt.lang
            if (opt.watchUrl.contains("nupload")) {
                if (resolveNupload(opt.watchUrl, label, callback)) any = true
            } else {
                // Otros posibles hosts: se delega a los extractores de CloudStream
                try {
                    val collected = mutableListOf<ExtractorLink>()
                    loadExtractor(opt.watchUrl, data, subtitleCallback) { link -> collected.add(link) }
                    for (link in collected) {
                        any = true
                        callback(
                            newExtractorLink("$name $label", "$name · $label (${link.name})", link.url) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.type = link.type
                                this.headers = link.headers
                            }
                        )
                    }
                } catch (e: Exception) {
                    // Si un servidor falla, se continúa con el resto
                }
            }
        }
        return any
    }
}
