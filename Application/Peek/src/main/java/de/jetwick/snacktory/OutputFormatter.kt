package de.jetwick.snacktory

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.regex.Pattern

/**
 * @author goose | jim
 * @author karussell
 *
 * this class will be responsible for taking our top node and stripping out junk
 * we don't want and getting it ready for how we want it presented to the user
 */
open class OutputFormatter @JvmOverloads constructor(
        protected val minParagraphText: Int = MIN_PARAGRAPH_TEXT,
        protected val nodesToReplace: List<String> = NODES_TO_REPLACE
) {
    private var unlikelyPattern = Pattern.compile("display\\:none|visibility\\:hidden")
    protected var nodesToKeepCssSelector = "p"

    /**
     * takes an element and turns the P tags into \n\n
     */
    fun getFormattedText(topNode: Element): String {
        removeNodesWithNegativeScores(topNode)
        val sb = StringBuilder()
        append(topNode, sb, nodesToKeepCssSelector)
        var str = SHelper.innerTrim(sb.toString())
        if (str.length > 100)
            return str

        // no subelements
        if (str.isEmpty() || topNode.text().isNotEmpty() && str.length <= topNode.ownText().length)
            str = topNode.text()

        // if jsoup failed to parse the whole html now parse this smaller
        // snippet again to avoid html tags disturbing our text:
        return Jsoup.parse(str).text()
    }

    /**
     * Takes an element and returns a list of texts extracted from the P tags
     */
    fun getTextList(topNode: Element): List<String> {
        val texts = ArrayList<String>()
        for (element in topNode.select(this.nodesToKeepCssSelector)) {
            if (element.hasText()) {
                texts.add(element.text())
            }
        }
        return texts
    }

    /**
     * If there are elements inside our top node that have a negative gravity
     * score remove them
     */
    protected fun removeNodesWithNegativeScores(topNode: Element) {
        val gravityItems = topNode.select("*[gravityScore]")
        for (item in gravityItems) {
            val score = Integer.parseInt(item.attr("gravityScore"))
            if (score < 0 || item.text().length < minParagraphText)
                item.remove()
        }
    }

    protected fun append(node: Element, sb: StringBuilder, tagName: String) {
        // is select more costly then getElementsByTag?
        main@ for (e in node.select(tagName)) {
            var tmpEl: Element? = e
            // check all elements until 'node'
            while (tmpEl != null && tmpEl != node) {
                if (unlikely(tmpEl))
                    continue@main
                tmpEl = tmpEl.parent()
            }

            val text = node2Text(e)
            if (text.isEmpty() || text.length < minParagraphText || text.length > SHelper.countLetters(text) * 2)
                continue

            sb.append(text)
            sb.append("\n\n")
        }
    }

    fun unlikely(e: Node): Boolean {
        if (e.attr("class") != null && e.attr("class").lowercase().contains("caption"))
            return true

        val style = e.attr("style")
        val clazz = e.attr("class")
        if (unlikelyPattern.matcher(style).find() || unlikelyPattern.matcher(clazz).find())
            return true
        return false
    }

    fun appendTextSkipHidden(e: Element, accum: StringBuilder) {
        for (child in e.childNodes()) {
            if (unlikely(child))
                continue
            if (child is TextNode) {
                val txt = child.text()
                accum.append(txt)
            } else if (child is Element) {
                if (accum.isNotEmpty() && child.isBlock && !lastCharIsWhitespace(accum))
                    accum.append(" ")
                else if (child.tagName() == "br")
                    accum.append(" ")
                appendTextSkipHidden(child, accum)
            }
        }
    }

    fun lastCharIsWhitespace(accum: StringBuilder): Boolean {
        if (accum.isEmpty())
            return false
        return Character.isWhitespace(accum[accum.length - 1])
    }

    protected fun node2TextOld(el: Element): String {
        return el.text()
    }

    protected fun node2Text(el: Element): String {
        val sb = StringBuilder(200)
        appendTextSkipHidden(el, sb)
        return sb.toString()
    }

    fun setUnlikelyPattern(unlikelyPattern: String): OutputFormatter {
        this.unlikelyPattern = Pattern.compile(unlikelyPattern)
        return this
    }

    fun appendUnlikelyPattern(str: String): OutputFormatter {
        return setUnlikelyPattern(unlikelyPattern.toString() + "|" + str)
    }

    companion object {
        const val MIN_PARAGRAPH_TEXT = 50
        private val NODES_TO_REPLACE = listOf("strong", "b", "i")
    }
}
