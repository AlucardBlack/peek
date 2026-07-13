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

import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.SocketTimeoutException
import java.nio.charset.Charset

/**
 * This class is not thread safe. Use one new instance every time due to
 * encoding variable.
 *
 * @author Peter Karich
 */
class Converter @JvmOverloads constructor(private val url: String? = null) {

    private var maxBytes = 1000000 / 2
    private var encoding: String? = null

    fun setMaxBytes(maxBytes: Int): Converter {
        this.maxBytes = maxBytes
        return this
    }

    fun getEncoding(): String {
        val enc = encoding ?: return ""
        return enc.lowercase()
    }

    @JvmOverloads
    fun streamToString(`is`: InputStream, enc: String? = encoding, maxBytes: Int = this.maxBytes): String {
        return streamToString(`is`, maxBytes, enc)
    }

    /**
     * reads bytes off the string and returns a string
     *
     * @param is
     * @param maxBytes The max bytes that we want to read from the input stream
     * @return String
     */
    fun streamToString(`is`: InputStream, maxBytes: Int, enc: String?): String {
        var encodingLocal = enc
        // Http 1.1. standard is iso-8859-1 not utf8 :(
        // but we force utf-8 as youtube assumes it ;)
        if (encodingLocal == null || encodingLocal.isEmpty())
            encodingLocal = UTF8
        encoding = encodingLocal

        var `in`: BufferedInputStream? = null
        try {
            `in` = BufferedInputStream(`is`, K2)
            val output = ByteArrayOutputStream()

            // detect encoding with the help of meta tag
            try {
                `in`.mark(K2 * 2)
                var tmpEnc = detectCharset("charset=", output, `in`, encodingLocal)
                if (tmpEnc != null)
                    encodingLocal = tmpEnc
                else {
                    logger.debug("no charset found in first stage")
                    // detect with the help of xml beginning ala encoding="charset"
                    tmpEnc = detectCharset("encoding=", output, `in`, encodingLocal)
                    if (tmpEnc != null)
                        encodingLocal = tmpEnc
                    else
                        logger.debug("no charset found in second stage")
                }

                if (!Charset.isSupported(encodingLocal))
                    throw UnsupportedEncodingException(encodingLocal)
            } catch (e: UnsupportedEncodingException) {
                logger.warn("Using default encoding:" + UTF8
                        + " problem:" + e.message + " encoding:" + encodingLocal + " " + url)
                encodingLocal = UTF8
            }
            encoding = encodingLocal

            // SocketException: Connection reset
            // IOException: missing CR    => problem on server (probably some xml character thing?)
            // IOException: Premature EOF => socket unexpectly closed from server
            var bytesRead = output.size()
            val arr = ByteArray(K2)
            while (true) {
                if (bytesRead >= maxBytes) {
                    logger.warn("Maxbyte of $maxBytes exceeded! Maybe html is now broken but try it nevertheless. Url: $url")
                    break
                }

                val n = `in`.read(arr)
                if (n < 0)
                    break
                bytesRead += n
                output.write(arr, 0, n)
            }

            return output.toString(encodingLocal)
        } catch (e: SocketTimeoutException) {
            logger.info(e.toString() + " url:" + url)
        } catch (e: IOException) {
            logger.warn(e.toString() + " url:" + url)
        } finally {
            if (`in` != null) {
                try {
                    `in`.close()
                } catch (e: Exception) {
                }
            }
        }
        return ""
    }

    /**
     * This method detects the charset even if the first call only returns some
     * bytes. It will read until 4K bytes are reached and then try to determine
     * the encoding
     *
     * @throws IOException
     */
    @Throws(IOException::class)
    fun detectCharset(key: String, bos: ByteArrayOutputStream, `in`: BufferedInputStream,
                       enc: String?): String? {

        // Grab better encoding from stream
        val arr = ByteArray(K2)
        var nSum = 0
        while (nSum < K2) {
            val n = `in`.read(arr)
            if (n < 0)
                break

            nSum += n
            bos.write(arr, 0, n)
        }

        val str = bos.toString(enc)
        val encIndex = str.indexOf(key)
        val clength = key.length
        if (encIndex > 0) {
            val startChar = str[encIndex + clength]
            var lastEncIndex: Int
            var mutableEncIndex = encIndex
            if (startChar == '\'')
            // if we have charset='something'
                lastEncIndex = str.indexOf("'", ++mutableEncIndex + clength)
            else if (startChar == '\"')
            // if we have charset="something"
                lastEncIndex = str.indexOf("\"", ++mutableEncIndex + clength)
            else {
                // if we have "text/html; charset=utf-8"
                var first = str.indexOf("\"", mutableEncIndex + clength)
                if (first < 0)
                    first = Integer.MAX_VALUE

                // or "text/html; charset=utf-8 "
                var sec = str.indexOf(" ", mutableEncIndex + clength)
                if (sec < 0)
                    sec = Integer.MAX_VALUE
                lastEncIndex = Math.min(first, sec)

                // or "text/html; charset=utf-8 '
                val third = str.indexOf("'", mutableEncIndex + clength)
                if (third > 0)
                    lastEncIndex = Math.min(lastEncIndex, third)
            }

            // re-read byte array with different encoding
            // assume that the encoding string cannot be greater than 40 chars
            if (lastEncIndex > mutableEncIndex + clength && lastEncIndex < mutableEncIndex + clength + 40) {
                val tmpEnc = SHelper.encodingCleanup(str.substring(mutableEncIndex + clength, lastEncIndex))
                try {
                    `in`.reset()
                    bos.reset()
                    return tmpEnc
                } catch (ex: IOException) {
                    logger.warn("Couldn't reset stream to re-read with new encoding $tmpEnc $ex")
                }
            }
        }
        return null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Converter::class.java)
        const val UTF8 = "UTF-8"
        const val ISO = "ISO-8859-1"
        const val K2 = 2048

        @JvmStatic
        fun extractEncoding(contentType: String?): String {
            val values: Array<String> = if (contentType != null)
                contentType.split(";").toTypedArray()
            else
                arrayOf()

            var charset = ""

            for (v in values) {
                val value = v.trim().lowercase()

                if (value.startsWith("charset="))
                    charset = value.substring("charset=".length)
            }

            // http1.1 says ISO-8859-1 is the default charset
            if (charset.isEmpty())
                charset = ISO

            return charset
        }
    }
}
