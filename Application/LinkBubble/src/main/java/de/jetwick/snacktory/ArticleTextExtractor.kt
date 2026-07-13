package de.jetwick.snacktory

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.slf4j.LoggerFactory
import java.net.MalformedURLException
import java.net.URL
import java.util.Collections
import java.util.Comparator
import java.util.Date
import java.util.LinkedHashMap
import java.util.regex.Pattern

/**
 * This class is thread safe.
 *
 * @author Alex P (ifesdjeen from jreadability)
 * @author Peter Karich
 */
class ArticleTextExtractor {

    // Unlikely candidates
    private var unlikelyStr: String? = null
    private var UNLIKELY: Pattern? = null

    // Most likely positive candidates
    private var positiveStr: String? = null
    private var POSITIVE: Pattern? = null

    // Most likely negative candidates
    private var negativeStr: String? = null
    private var NEGATIVE: Pattern? = null

    private var formatter: OutputFormatter = DEFAULT_FORMATTER

    init {
        setUnlikely("com(bx|ment|munity)|dis(qus|cuss)|e(xtra|[-]?mail)|foot|"
                + "header|menu|re(mark|ply)|rss|sh(are|outbox)|sponsor"
                + "a(d|ll|gegate|rchive|ttachment)|(pag(er|ination))|popup|print|"
                + "login|si(debar|gn|ngle)")
        setPositive("(^(body|content|h?entry|main|page|post|text|blog|story|haupt))"
                + "|arti(cle|kel)|instapaper_body")
        setNegative("nav($|igation)|user|com(ment|bx)|(^com-)|contact|"
                + "foot|masthead|(me(dia|ta))|outbrain|promo|related|scroll|(sho(utbox|pping))|"
                + "sidebar|sponsor|tags|tool|widget|player|disclaimer|toc|infobox|vcard")
    }

    fun setUnlikely(unlikelyStr: String): ArticleTextExtractor {
        this.unlikelyStr = unlikelyStr
        UNLIKELY = Pattern.compile(unlikelyStr)
        return this
    }

    fun addUnlikely(unlikelyMatches: String): ArticleTextExtractor {
        return setUnlikely(unlikelyStr + "|" + unlikelyMatches)
    }

    fun setPositive(positiveStr: String): ArticleTextExtractor {
        this.positiveStr = positiveStr
        POSITIVE = Pattern.compile(positiveStr)
        return this
    }

    fun addPositive(pos: String): ArticleTextExtractor {
        return setPositive(positiveStr + "|" + pos)
    }

    fun setNegative(negativeStr: String): ArticleTextExtractor {
        this.negativeStr = negativeStr
        NEGATIVE = Pattern.compile(negativeStr)
        return this
    }

    fun addNegative(neg: String): ArticleTextExtractor {
        setNegative(negativeStr + "|" + neg)
        return this
    }

    fun setOutputFormatter(formatter: OutputFormatter) {
        this.formatter = formatter
    }

    // Returns the best node match based on the weights
    private fun getBestMatchElement(nodes: Collection<Element>): Element? {
        var maxWeight = -200
        var bestMatchElement: Element? = null

        for (entry in nodes) {
            val currentWeight = getWeight(entry, false)
            if (currentWeight > maxWeight) {
                maxWeight = currentWeight
                bestMatchElement = entry
                if (maxWeight > 200)
                    break
            }
        }

        return bestMatchElement
    }

    /**
     * @param html extracts article text from given html string. wasn't tested
     * with improper HTML, although jSoup should be able to handle minor stuff.
     * @returns extracted article, all HTML tags stripped
     */
    @Throws(Exception::class)
    fun extractContent(doc: Document): JResult {
        return extractContent(JResult(), doc, formatter)
    }

    @Throws(Exception::class)
    fun extractContent(doc: Document, formatter: OutputFormatter): JResult {
        return extractContent(JResult(), doc, formatter)
    }

    @Throws(Exception::class)
    fun extractContent(html: String): JResult {
        return extractContent(JResult(), html)
    }

    @Throws(Exception::class)
    fun extractContent(res: JResult, html: String): JResult {
        return extractContent(res, html, formatter)
    }

    @Throws(Exception::class)
    fun extractContent(res: JResult, html: String, formatter: OutputFormatter): JResult {
        if (html.isEmpty())
            throw IllegalArgumentException("html string is empty!?")

        // http://jsoup.org/cookbook/extracting-data/selector-syntax
        return extractContent(res, Jsoup.parse(html), formatter)
    }

    @Throws(Exception::class)
    fun extractContent(res: JResult, doc: Document?, formatter: OutputFormatter): JResult {
        if (doc == null)
            throw NullPointerException("missing document")

        res.setTitle(extractTitle(doc))
        res.setDescription(extractDescription(doc))
        res.setCanonicalUrl(extractCanonicalUrl(doc))

        res.setAuthorName(extractAuthorName(doc))
        res.setAuthorDescription(extractAuthorDescription(doc, res.getAuthorName()))

        // get date from document, if not present, extract from URL if possible
        var docdate = extractDate(doc)
        if (docdate == null) {
            val dateStr = SHelper.estimateDate(res.getUrl())
            docdate = parseDate(dateStr)
            res.setDate(docdate)
        } else {
            res.setDate(docdate)
        }

        // now remove the clutter
        prepareDocument(doc)

        // init elements
        val nodes = getNodes(doc)
        val bestMatchElement = getBestMatchElement(nodes)

        if (bestMatchElement != null) {
            val images = ArrayList<ImageResult>()
            val imgEl = determineImageSource(bestMatchElement, images)
            if (imgEl != null) {
                res.setImageUrl(SHelper.replaceSpaces(imgEl.attr("src")))
                // TODO remove parent container of image if it is contained in bestMatchElement
                // to avoid image subtitles flooding in

                res.setImages(images)
            }

            // clean before grabbing text
            var text = formatter.getFormattedText(bestMatchElement)
            text = removeTitleFromText(text, res.getTitle())
            // this fails for short facebook post and probably tweets: text.length() > res.getDescription().length()
            if (text.length > res.getTitle().length) {
                res.setText(text)

                val fullHtml = bestMatchElement.toString()
                res.setHtml(fullHtml)

                val children = bestMatchElement.select("a[href]") // a with href = link
                var linkstr: String
                var linkpos = 0
                var lastlinkpos = 0
                for (child in children) {
                    linkstr = child.toString()
                    linkpos = fullHtml.indexOf(linkstr, lastlinkpos)
                    res.addLink(child.attr("abs:href"), child.text(), linkpos)
                    lastlinkpos = linkpos
                }
            }
            res.setTextList(formatter.getTextList(bestMatchElement))
        }

        if (res.getImageUrl().isEmpty()) {
            res.setImageUrl(extractImageUrl(doc))
        }

        res.setRssUrl(extractRssUrl(doc))
        res.setVideoUrl(extractVideoUrl(doc))
        res.setFaviconUrl(extractFaviconUrl(doc))
        res.setKeywords(extractKeywords(doc))
        return res
    }

    protected fun extractTitle(doc: Document): String {
        var title = cleanTitle(doc.title())
        if (title.isEmpty()) {
            title = SHelper.innerTrim(doc.select("head title").text())
            if (title.isEmpty()) {
                title = SHelper.innerTrim(doc.select("head meta[name=title]").attr("content"))
                if (title.isEmpty()) {
                    title = SHelper.innerTrim(doc.select("head meta[property=og:title]").attr("content"))
                    if (title.isEmpty()) {
                        title = SHelper.innerTrim(doc.select("head meta[name=twitter:title]").attr("content"))
                    }
                }
            }
        }
        return title
    }

    protected fun extractCanonicalUrl(doc: Document): String {
        var url = SHelper.replaceSpaces(doc.select("head link[rel=canonical]").attr("href"))
        if (url.isEmpty()) {
            url = SHelper.replaceSpaces(doc.select("head meta[property=og:url]").attr("content"))
            if (url.isEmpty()) {
                url = SHelper.replaceSpaces(doc.select("head meta[name=twitter:url]").attr("content"))
            }
        }
        return url
    }

    protected fun extractDescription(doc: Document): String {
        var description = SHelper.innerTrim(doc.select("head meta[name=description]").attr("content"))
        if (description.isEmpty()) {
            description = SHelper.innerTrim(doc.select("head meta[property=og:description]").attr("content"))
            if (description.isEmpty()) {
                description = SHelper.innerTrim(doc.select("head meta[name=twitter:description]").attr("content"))
            }
        }
        return description
    }

    // Returns the publication Date or null
    protected fun extractDate(doc: Document): Date? {
        var dateStr: String

        // try some locations that nytimes uses
        val elem = doc.select("meta[name=ptime]").first()
        dateStr = if (elem != null) {
            SHelper.innerTrim(elem.attr("content"))
            //            elem.attr("extragravityscore", Integer.toString(100));
            //            System.out.println("date modified element " + elem.toString());
        } else {
            ""
        }

        if (dateStr == "") {
            dateStr = SHelper.innerTrim(doc.select("meta[name=utime]").attr("content"))
        }
        if (dateStr == "") {
            dateStr = SHelper.innerTrim(doc.select("meta[name=pdate]").attr("content"))
        }
        if (dateStr == "") {
            dateStr = SHelper.innerTrim(doc.select("meta[property=article:published]").attr("content"))
        }
        if (dateStr != "") {
            return parseDate(dateStr)
        }

        // taking this stuff directly from Juicer (and converted to Java)
        // opengraph (?)
        var elems = doc.select("meta[property=article:published_time]")
        if (elems.size > 0) {
            val el = elems[0]
            if (el.hasAttr("content")) {
                dateStr = el.attr("content")

                dateStr = if (dateStr.endsWith("Z")) {
                    dateStr.substring(0, dateStr.length - 1) + "GMT-00:00"
                } else {
                    "%sGMT%s".format(dateStr.substring(0, dateStr.length - 6),
                            dateStr.substring(dateStr.length - 6,
                                    dateStr.length))
                }

                return parseDate(dateStr)
            }
        }

        // rnews
        elems = doc.select("meta[property=dateCreated], span[property=dateCreated]")
        if (elems.size > 0) {
            val el = elems[0]
            return if (el.hasAttr("content")) {
                dateStr = el.attr("content")

                parseDate(dateStr)
            } else {
                parseDate(el.text())
            }
        }

        // schema.org creativework
        elems = doc.select("meta[itemprop=datePublished], span[itemprop=datePublished]")
        if (elems.size > 0) {
            val el = elems[0]
            if (el.hasAttr("content")) {
                dateStr = el.attr("content")

                return parseDate(dateStr)
            } else if (el.hasAttr("value")) {
                dateStr = el.attr("value")

                return parseDate(dateStr)
            } else {
                return parseDate(el.text())
            }
        }

        // parsely page (?)
        /*  skip conversion for now, seems highly specific and uses new lib
        elems = doc.select("meta[name=parsely-page]");
        if (elems.size() > 0) {
            implicit val formats = net.liftweb.json.DefaultFormats

                Element el = elems.get(0);
                if(el.hasAttr("content")) {
                    val json = parse(el.attr("content"))

                        return DateUtils.parseDateStrictly((json \ "pub_date").extract[String], Array("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssZZ", "yyyy-MM-dd'T'HH:mm:ssz"))
                        }
            }
        */

        // BBC
        elems = doc.select("meta[name=OriginalPublicationDate]")
        if (elems.size > 0) {
            val el = elems[0]
            if (el.hasAttr("content")) {
                dateStr = el.attr("content")
                return parseDate(dateStr)
            }
        }

        // wired
        elems = doc.select("meta[name=DisplayDate]")
        if (elems.size > 0) {
            val el = elems[0]
            if (el.hasAttr("content")) {
                dateStr = el.attr("content")
                return parseDate(dateStr)
            }
        }

        // wildcard
        elems = doc.select("meta[name*=date]")
        if (elems.size > 0) {
            val el = elems[0]
            if (el.hasAttr("content")) {
                dateStr = el.attr("content")
                return parseDate(dateStr)
            }
        }

        return null
    }

    private fun parseDate(dateStr: String?): Date? {
        val parsePatterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ssz",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy/MM/dd HH:mm",
                "yyyy-MM-dd",
                "yyyy/MM/dd",
                "MM/dd/yyyy HH:mm:ss",
                "MM-dd-yyyy HH:mm:ss",
                "MM/dd/yyyy HH:mm",
                "MM-dd-yyyy HH:mm",
                "MM/dd/yyyy",
                "MM-dd-yyyy",
                "EEE, MMM dd, yyyy",
                "MM/dd/yyyy hh:mm:ss a",
                "MM-dd-yyyy hh:mm:ss a",
                "MM/dd/yyyy hh:mm a",
                "MM-dd-yyyy hh:mm a",
                "yyyy-MM-dd hh:mm:ss a",
                "yyyy/MM/dd hh:mm:ss a ",
                "yyyy-MM-dd hh:mm a",
                "yyyy/MM/dd hh:mm ",
                "dd MMM yyyy",
                "dd MMMM yyyy",
                "yyyyMMddHHmm",
                "yyyyMMdd HHmm",
                "dd-MM-yyyy HH:mm:ss",
                "dd/MM/yyyy HH:mm:ss",
                "dd MMM yyyy HH:mm:ss",
                "dd MMMM yyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm",
                "dd/MM/yyyy HH:mm",
                "dd MMM yyyy HH:mm",
                "dd MMMM yyyy HH:mm",
                "yyyyMMddHHmmss",
                "yyyyMMdd HHmmss",
                "yyyyMMdd"
        )

        return try {
            Util.parseDateStrictly(dateStr, parsePatterns)
        } catch (ex: Exception) {
            null
        }
    }

    private fun cleanAuthorNameResult(resultIn: String): String {
        val result = SHelper.innerTrim(resultIn)

        return try {
            // If the result is a valid URL, it's not an author name, so ignore it. Was an issue with http://www.engadget.com/2014/04/29/freedompop-iphone/
            URL(result)
            ""
        } catch (e: MalformedURLException) {
            result
        }
    }

    // Returns the author name or null
    protected fun extractAuthorName(doc: Document): String {
        var authorName = ""

        // first try the Google Author tag
        var result = doc.select("body [rel*=author]").first()
        if (result != null) {
            authorName = cleanAuthorNameResult(result.ownText())
        }

        // if that doesn't work, try some other methods
        if (authorName.isEmpty()) {

            // meta tag approaches, get content
            result = doc.select("head meta[name=author]").first()
            if (result != null) {
                authorName = cleanAuthorNameResult(result.attr("content"))
            }

            if (authorName.isEmpty()) {  // for "opengraph"
                authorName = cleanAuthorNameResult(doc.select("head meta[property=article:author]").attr("content"))
            }
            if (authorName.isEmpty()) {  // for "schema.org creativework"
                authorName = cleanAuthorNameResult(doc.select("meta[itemprop=author], span[itemprop=author]").attr("content"))
            }

            // other hacks
            if (authorName.isEmpty()) {
                try {
                    // build up a set of elements which have likely author-related terms
                    // .X searches for class X
                    var matches: Elements? = doc.select(".byLineTag,.byline,.author,.by,.writer,.address")

                    if (matches == null || matches.size == 0) {
                        matches = doc.select("body [class*=author]")
                    }

                    if (matches == null || matches.size == 0) {
                        matches = doc.select("body [title*=author]")
                    }

                    // a hack for huffington post
                    if (matches == null || matches.size == 0) {
                        matches = doc.select(".staff_info dl a[href]")
                    }

                    // select the best element from them
                    if (matches != null) {
                        val bestMatch = getBestMatchElement(matches)

                        if (bestMatch != null) {
                            authorName = bestMatch.ownText()

                            if (authorName.length < MIN_AUTHOR_NAME_LENGTH) {
                                authorName = bestMatch.text()
                            }

                            authorName = cleanAuthorNameResult(IGNORE_AUTHOR_PARTS.matcher(authorName).replaceAll(""))

                            // Remove twitter handle: http://www.engadget.com/2014/04/29/freedompop-iphone/
                            val atIndex = authorName.indexOf("@")
                            if (atIndex > 0) {
                                authorName = cleanAuthorNameResult(authorName.substring(0, atIndex))
                            }

                            if (authorName.indexOf(",") != -1) {
                                authorName = authorName.split(",")[0]
                            }
                        }
                    }
                } catch (e: Exception) {
                    println(e.toString())
                }
            }
        }
        return authorName
    }

    // Returns the author description or null
    protected fun extractAuthorDescription(doc: Document, authorName: String): String {

        var authorDesc = ""

        if (authorName == "")
            return ""

        val nodes = doc.select(":containsOwn($authorName)")

        val bestMatch = getBestMatchElement(nodes)

        if (bestMatch != null)
            authorDesc = bestMatch.text()

        return authorDesc
    }

    protected fun extractKeywords(doc: Document): Collection<String> {
        var content: String? = SHelper.innerTrim(doc.select("head meta[name=keywords]").attr("content"))

        if (content != null) {
            if (content.startsWith("[") && content.endsWith("]"))
                content = content.substring(1, content.length - 1)

            val split = content.split(Regex("\\s*,\\s*"))
            if (split.size > 1 || (split.isNotEmpty() && "" != split[0]))
                return split
        }
        return Collections.emptyList()
    }

    /**
     * Tries to extract an image url from metadata if determineImageSource
     * failed
     *
     * @return image url or empty str
     */
    protected fun extractImageUrl(doc: Document): String {
        // use open graph tag to get image
        var imageUrl = SHelper.replaceSpaces(doc.select("head meta[property=og:image]").attr("content"))
        if (imageUrl.isEmpty()) {
            imageUrl = SHelper.replaceSpaces(doc.select("head meta[name=twitter:image]").attr("content"))
            if (imageUrl.isEmpty()) {
                // prefer link over thumbnail-meta if empty
                imageUrl = SHelper.replaceSpaces(doc.select("link[rel=image_src]").attr("href"))
                if (imageUrl.isEmpty()) {
                    imageUrl = SHelper.replaceSpaces(doc.select("head meta[name=thumbnail]").attr("content"))
                }
            }
        }
        return imageUrl
    }

    protected fun extractRssUrl(doc: Document): String {
        return SHelper.replaceSpaces(doc.select("link[rel=alternate]").select("link[type=application/rss+xml]").attr("href"))
    }

    protected fun extractVideoUrl(doc: Document): String {
        return SHelper.replaceSpaces(doc.select("head meta[property=og:video]").attr("content"))
    }

    protected fun extractFaviconUrl(doc: Document): String {
        var faviconUrl = SHelper.replaceSpaces(doc.select("head link[rel=icon]").attr("href"))
        if (faviconUrl.isEmpty()) {
            faviconUrl = SHelper.replaceSpaces(doc.select("head link[rel^=shortcut],link[rel$=icon]").attr("href"))
        }
        return faviconUrl
    }

    /**
     * Weights current element. By matching it with positive candidates and
     * weighting child nodes. Since it's impossible to predict which exactly
     * names, ids or class names will be used in HTML, major role is played by
     * child nodes
     *
     * @param e Element to weight, along with child nodes
     */
    protected fun getWeight(e: Element, checkextra: Boolean): Int {
        var weight = calcWeight(e)
        weight += Math.round(e.ownText().length / 100.0 * 10).toInt()
        weight += weightChildNodes(e)

        // add additional weight using possible 'extragravityscore' attribute
        if (checkextra) {
            val xelem = e.select("[extragravityscore]").first()
            if (xelem != null) {
                //                System.out.println("HERE found one: " + xelem.toString());
                weight += Integer.parseInt(xelem.attr("extragravityscore"))
                //                System.out.println("WITH WEIGHT: " + xelem.attr("extragravityscore"));
            }
        }

        return weight
    }

    /**
     * Weights a child nodes of given Element. During tests some difficulties
     * were met. For instanance, not every single document has nested paragraph
     * tags inside of the major article tag. Sometimes people are adding one
     * more nesting level. So, we're adding 4 points for every 100 symbols
     * contained in tag nested inside of the current weighted element, but only
     * 3 points for every element that's nested 2 levels deep. This way we give
     * more chances to extract the element that has less nested levels,
     * increasing probability of the correct extraction.
     *
     * @param rootEl Element, who's child nodes will be weighted
     */
    protected fun weightChildNodes(rootEl: Element): Int {
        var weight = 0
        var caption: Element? = null
        val pEls = ArrayList<Element>(5)
        for (child in rootEl.children()) {
            val ownText = child.ownText()
            val ownTextLength = ownText.length
            if (ownTextLength < 20)
                continue

            if (ownTextLength > 200)
                weight += Math.max(50, ownTextLength / 10)

            if (child.tagName() == "h1" || child.tagName() == "h2") {
                weight += 30
            } else if (child.tagName() == "div" || child.tagName() == "p") {
                weight += calcWeightForChild(child, ownText)
                if (child.tagName() == "p" && ownTextLength > 50)
                    pEls.add(child)

                if (child.className().lowercase() == "caption")
                    caption = child
            }
        }

        // use caption and image
        if (caption != null)
            weight += 30

        if (pEls.size >= 2) {
            for (subEl in rootEl.children()) {
                if ("h1;h2;h3;h4;h5;h6".contains(subEl.tagName())) {
                    weight += 20
                    // headerEls.add(subEl);
                } else if ("table;li;td;th".contains(subEl.tagName())) {
                    addScore(subEl, -30)
                }

                if ("p".contains(subEl.tagName()))
                    addScore(subEl, 30)
            }
        }
        return weight
    }

    fun addScore(el: Element, score: Int) {
        val old = getScore(el)
        setScore(el, score + old)
    }

    fun getScore(el: Element): Int {
        var old = 0
        try {
            old = Integer.parseInt(el.attr("gravityScore"))
        } catch (ex: Exception) {
        }
        return old
    }

    fun setScore(el: Element, score: Int) {
        el.attr("gravityScore", Integer.toString(score))
    }

    private fun calcWeightForChild(child: Element, ownText: String): Int {
        var c = SHelper.count(ownText, "&quot;")
        c += SHelper.count(ownText, "&lt;")
        c += SHelper.count(ownText, "&gt;")
        c += SHelper.count(ownText, "px")
        val `val`: Int
        `val` = if (c > 5)
            -30
        else
            Math.round(ownText.length / 25.0).toInt()

        addScore(child, `val`)
        return `val`
    }

    private fun calcWeight(e: Element): Int {
        var weight = 0
        if (POSITIVE!!.matcher(e.className()).find())
            weight += 35

        if (POSITIVE!!.matcher(e.id()).find())
            weight += 40

        if (UNLIKELY!!.matcher(e.className()).find())
            weight -= 20

        if (UNLIKELY!!.matcher(e.id()).find())
            weight -= 20

        if (NEGATIVE!!.matcher(e.className()).find())
            weight -= 50

        if (NEGATIVE!!.matcher(e.id()).find())
            weight -= 50

        val style = e.attr("style")
        if (style != null && style.isNotEmpty() && NEGATIVE_STYLE.matcher(style).find())
            weight -= 50
        return weight
    }

    fun determineImageSource(el: Element, images: MutableList<ImageResult>): Element? {
        var maxWeight = 0
        var maxNode: Element? = null
        var els = el.select("img")
        if (els.isEmpty())
            els = el.parent()!!.select("img")

        var score = 1.0
        for (e in els) {
            val sourceUrl = e.attr("src")
            if (sourceUrl.isEmpty() || isAdImage(sourceUrl))
                continue

            var weight = 0
            var height = 0
            try {
                height = Integer.parseInt(e.attr("height"))
                if (height >= 50)
                    weight += 20
                else
                    weight -= 20
            } catch (ex: Exception) {
            }

            var width = 0
            try {
                width = Integer.parseInt(e.attr("width"))
                if (width >= 50)
                    weight += 20
                else
                    weight -= 20
            } catch (ex: Exception) {
            }
            val alt = e.attr("alt")
            if (alt.length > 35)
                weight += 20

            val title = e.attr("title")
            if (title.length > 35)
                weight += 20

            var rel: String? = null
            var noFollow = false
            if (e.parent() != null) {
                rel = e.parent()!!.attr("rel")
                if (rel != null && rel.contains("nofollow")) {
                    noFollow = rel.contains("nofollow")
                    weight -= 40
                }
            }

            weight = (weight * score).toInt()
            if (weight > maxWeight) {
                maxWeight = weight
                maxNode = e
                score /= 2
            }

            val image = ImageResult(sourceUrl, weight, title, height, width, alt, noFollow)
            images.add(image)
        }

        Collections.sort(images, ImageComparator())
        return maxNode
    }

    /**
     * Prepares document. Currently only stipping unlikely candidates, since
     * from time to time they're getting more score than good ones especially in
     * cases when major text is short.
     *
     * @param doc document to prepare. Passed as reference, and changed inside
     * of function
     */
    protected fun prepareDocument(doc: Document) {
        //        stripUnlikelyCandidates(doc);
        removeScriptsAndStyles(doc)
    }

    /**
     * Removes unlikely candidates from HTML. Currently takes id and class name
     * and matches them against list of patterns
     *
     * @param doc document to strip unlikely candidates from
     */
    protected fun stripUnlikelyCandidates(doc: Document) {
        for (child in doc.select("body").select("*")) {
            val className = child.className().lowercase()
            val id = child.id().lowercase()

            if (NEGATIVE!!.matcher(className).find()
                    || NEGATIVE!!.matcher(id).find()) {
                //                print("REMOVE:", child);
                child.remove()
            }
        }
    }

    private fun removeScriptsAndStyles(doc: Document): Document {
        val scripts = doc.getElementsByTag("script")
        for (item in scripts) {
            item.remove()
        }

        val noscripts = doc.getElementsByTag("noscript")
        for (item in noscripts) {
            item.remove()
        }

        val styles = doc.getElementsByTag("style")
        for (style in styles) {
            style.remove()
        }

        return doc
    }

    private fun print(child: Element) {
        print("", child, "")
    }

    private fun print(add: String, child: Element) {
        print(add, child, "")
    }

    private fun print(add1: String, child: Element, add2: String) {
        logger.info(add1 + " " + child.nodeName() + " id=" + child.id()
                + " class=" + child.className() + " text=" + child.text() + " " + add2)
    }

    private fun isAdImage(imageUrl: String): Boolean {
        return SHelper.count(imageUrl, "ad") >= 2
    }

    /**
     * Match only exact matching as longestSubstring can be too fuzzy
     */
    fun removeTitleFromText(text: String, title: String): String {
        // don't do this as its terrible to read
        //        int index1 = text.toLowerCase().indexOf(title.toLowerCase());
        //        if (index1 >= 0)
        //            text = text.substring(index1 + title.length());
        //        return text.trim();
        return text
    }

    /**
     * based on a delimeter in the title take the longest piece or do some
     * custom logic based on the site
     *
     * @param title
     * @param delimeter
     * @return
     */
    private fun doTitleSplits(title: String, delimeter: String): String {
        var largeText = ""
        var largetTextLen = 0
        val titlePieces = title.split(delimeter.toRegex())

        // take the largest split
        for (p in titlePieces) {
            if (p.length > largetTextLen) {
                largeText = p
                largetTextLen = p.length
            }
        }

        largeText = largeText.replace("&raquo;", " ")
        largeText = largeText.replace("»", " ")
        return largeText.trim()
    }

    /**
     * @return a set of all important nodes
     */
    fun getNodes(doc: Document): Collection<Element> {
        val nodes = LinkedHashMap<Element, Any?>(64)
        var score = 100
        for (el in doc.select("body").select("*")) {
            if (NODES.matcher(el.tagName()).matches()) {
                nodes[el] = null
                setScore(el, score)
                score /= 2
            }
        }
        return nodes.keys
    }

    fun cleanTitle(titleIn: String): String {
        val res = StringBuilder()
        //        int index = title.lastIndexOf("|");
        //        if (index > 0 && title.length() / 2 < index)
        //            title = title.substring(0, index + 1);

        var counter = 0
        val strs = titleIn.split(Regex("\\|"))
        for (part in strs) {
            if (IGNORED_TITLE_PARTS.contains(part.lowercase().trim()))
                continue

            if (counter == strs.size - 1 && res.length > part.length)
                continue

            if (counter > 0)
                res.append("|")

            res.append(part)
            counter++
        }

        return SHelper.innerTrim(res.toString())
    }

    /**
     * Comparator for Image by weight
     *
     * @author Chris Alexander, chris@chris-alexander.co.uk
     */
    inner class ImageComparator : Comparator<ImageResult> {

        override fun compare(o1: ImageResult, o2: ImageResult): Int {
            // Returns the highest weight first
            return o2.weight!!.compareTo(o1.weight!!)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ArticleTextExtractor::class.java)

        // Interessting nodes
        private val NODES = Pattern.compile("p|div|td|h1|h2|article|section")
        private val NEGATIVE_STYLE =
                Pattern.compile("hidden|display: ?none|font-size: ?small")
        private val IGNORE_AUTHOR_PARTS =
                Pattern.compile("by|name|author|posted|twitter|handle|news", Pattern.CASE_INSENSITIVE)
        private val IGNORED_TITLE_PARTS = linkedSetOf("hacker news", "facebook")
        private val DEFAULT_FORMATTER = OutputFormatter()

        private const val MIN_AUTHOR_NAME_LENGTH = 4
    }
}
