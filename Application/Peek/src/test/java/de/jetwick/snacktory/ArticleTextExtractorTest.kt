/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package de.jetwick.snacktory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime coverage for the snacktory article extractor. Because the whole
 * snacktory package is pure JVM code that parses HTML with jsoup, this test
 * exercises the jsoup dependency end-to-end (not just at compile time), which
 * is what makes it a meaningful guard for the jsoup version bump.
 */
class ArticleTextExtractorTest {

    private val html = """
        <html>
          <head><title>The Title of the Article</title></head>
          <body>
            <div id="nav"><a href="/">Home</a> <a href="/about">About</a></div>
            <div class="article">
              <h1>The Title of the Article</h1>
              <p>Peek is a lightweight Android browser that opens links in a
                 floating bubble so you can keep your place while you keep
                 reading. This paragraph exists to give the content extractor
                 enough text to confidently select this block as the main
                 article body rather than the surrounding chrome.</p>
              <p>A second paragraph adds further weight to the article node,
                 ensuring the scoring algorithm prefers it over the navigation
                 links and the footer boilerplate around it.</p>
            </div>
            <div id="footer">Copyright 2026 Example Inc.</div>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun extractsTitle() {
        val result = ArticleTextExtractor().extractContent(html)
        assertEquals("The Title of the Article", result.getTitle())
    }

    @Test
    fun extractsMainBodyText() {
        val text = ArticleTextExtractor().extractContent(html).getText()
        assertTrue("expected the first paragraph in: $text", text.contains("floating bubble"))
        assertTrue("expected the second paragraph in: $text", text.contains("second paragraph"))
    }

    @Test
    fun emptyHtmlThrows() {
        try {
            ArticleTextExtractor().extractContent("")
            throw AssertionError("expected IllegalArgumentException for empty html")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
