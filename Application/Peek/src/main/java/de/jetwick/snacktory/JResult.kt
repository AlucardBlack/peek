/*
 *  Copyright 2011 Peter Karich
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package de.jetwick.snacktory

import java.io.Serializable
import java.util.Collections
import java.util.Date

/**
 * Parsed result from web page containing important title, text and image.
 *
 * @author Peter Karich
 */
class JResult : Serializable {

    private var title: String? = null
    private var url: String? = null
    private var originalUrl: String? = null
    private var canonicalUrl: String? = null
    private var imageUrl: String? = null
    private var videoUrl: String? = null
    private var rssUrl: String? = null
    private var text: String? = null
    private var html: String? = null
    private var faviconUrl: String? = null
    private var authorName: String? = null
    private var authorDescription: String? = null
    private var description: String? = null
    private var dateString: String? = null
    private var date: Date? = null
    private var textList: List<String>? = null
    private var keywords: Collection<String>? = null
    private var images: List<ImageResult>? = null
    private var links: MutableList<Map<String, String>> = ArrayList()

    fun getUrl(): String {
        if (url == null)
            return ""
        return url!!
    }

    fun setUrl(url: String?): JResult {
        this.url = url
        return this
    }

    fun setOriginalUrl(originalUrl: String?): JResult {
        this.originalUrl = originalUrl
        return this
    }

    fun getOriginalUrl(): String? {
        return originalUrl
    }

    fun setCanonicalUrl(canonicalUrl: String?): JResult {
        this.canonicalUrl = canonicalUrl
        return this
    }

    fun getCanonicalUrl(): String? {
        return canonicalUrl
    }

    fun getFaviconUrl(): String {
        if (faviconUrl == null)
            return ""
        return faviconUrl!!
    }

    fun setFaviconUrl(faviconUrl: String?): JResult {
        this.faviconUrl = faviconUrl
        return this
    }

    fun setRssUrl(rssUrl: String?): JResult {
        this.rssUrl = rssUrl
        return this
    }

    fun getRssUrl(): String {
        if (rssUrl == null)
            return ""
        return rssUrl!!
    }

    fun getAuthorName(): String {
        if (authorName == null)
            return ""
        return authorName!!
    }

    fun setAuthorName(authorName: String?): JResult {
        this.authorName = authorName
        return this
    }

    fun getDescription(): String {
        if (description == null)
            return ""
        return description!!
    }

    fun setDescription(description: String?): JResult {
        this.description = description
        return this
    }

    fun getAuthorDescription(): String {
        if (authorDescription == null)
            return ""
        return authorDescription!!
    }

    fun setAuthorDescription(authorDescription: String?): JResult {
        this.authorDescription = authorDescription
        return this
    }

    fun getImageUrl(): String {
        if (imageUrl == null)
            return ""
        return imageUrl!!
    }

    fun setImageUrl(imageUrl: String?): JResult {
        this.imageUrl = imageUrl
        return this
    }

    fun getText(): String {
        if (text == null)
            return ""

        return text!!
    }

    fun setText(text: String?): JResult {
        this.text = text
        return this
    }

    fun setHtml(html: String?): JResult {
        this.html = html
        return this
    }

    fun getHtml(): String {
        if (html == null) {
            return ""
        }

        return html!!
    }

    fun getTextList(): List<String> {
        if (this.textList == null)
            return ArrayList()
        return this.textList!!
    }

    fun setTextList(textList: List<String>?): JResult {
        this.textList = textList
        return this
    }

    fun getTitle(): String {
        if (title == null)
            return ""
        return title!!
    }

    fun setTitle(title: String?): JResult {
        this.title = title
        return this
    }

    fun getVideoUrl(): String {
        if (videoUrl == null)
            return ""
        return videoUrl!!
    }

    fun setVideoUrl(videoUrl: String?): JResult {
        this.videoUrl = videoUrl
        return this
    }

    fun setDate(date: Date?): JResult {
        this.date = date
        return this
    }

    fun getKeywords(): Collection<String>? {
        return keywords
    }

    fun setKeywords(keywords: Collection<String>?) {
        this.keywords = keywords
    }

    /**
     * @return get date from url or guessed from text
     */
    fun getDate(): Date? {
        return date
    }

    /**
     * @return images list
     */
    fun getImages(): List<ImageResult> {
        if (images == null)
            return Collections.emptyList()
        return images!!
    }

    /**
     * @return images count
     */
    fun getImagesCount(): Int {
        if (images == null)
            return 0
        return images!!.size
    }

    /**
     * set images list
     */
    fun setImages(images: List<ImageResult>?) {
        this.images = images
    }

    fun addLink(url: String?, text: String?, pos: Int?) {
        val link = HashMap<String, String>()
        link["url"] = url ?: ""
        link["text"] = text ?: ""
        link["offset"] = pos.toString()
        links.add(link)
    }

    fun getLinks(): List<Map<String, String>> {
        return links
    }

    override fun toString(): String {
        return "title:" + getTitle() + " imageUrl:" + getImageUrl() + " text:" + text
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
