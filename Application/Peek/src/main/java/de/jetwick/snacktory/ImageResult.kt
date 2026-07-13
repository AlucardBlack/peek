package de.jetwick.snacktory

import org.jsoup.nodes.Element

/**
 * Class which encapsulates the data from an image found under an element
 *
 * @author Chris Alexander, chris@chris-alexander.co.uk
 */
class ImageResult(
        @JvmField var src: String?,
        @JvmField var weight: Int?,
        @JvmField var title: String?,
        @JvmField var height: Int,
        @JvmField var width: Int,
        @JvmField var alt: String?,
        @JvmField var noFollow: Boolean
) {
    @JvmField
    var element: Element? = null
}
