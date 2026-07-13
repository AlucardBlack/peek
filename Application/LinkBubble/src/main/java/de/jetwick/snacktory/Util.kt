package de.jetwick.snacktory

import java.text.ParseException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date

object Util {

    /**
     * Reformat the timezone in a date string.
     *
     * @param str The input string
     * @param signIdx The index position of the sign characters
     * @return The reformatted string
     */
    private fun reformatTimezone(str: String, signIdx: Int): String {
        var str2 = str
        if (signIdx >= 0 &&
                signIdx + 5 < str.length &&
                Character.isDigit(str[signIdx + 1]) &&
                Character.isDigit(str[signIdx + 2]) &&
                str[signIdx + 3] == ':' &&
                Character.isDigit(str[signIdx + 4]) &&
                Character.isDigit(str[signIdx + 5])) {
            str2 = str.substring(0, signIdx + 3) + str.substring(signIdx + 4)
        }
        return str2
    }

    // Empty checks
    //-----------------------------------------------------------------------
    /**
     * Checks if a String is empty ("") or null.
     *
     * StringUtils.isEmpty(null)      = true
     * StringUtils.isEmpty("")        = true
     * StringUtils.isEmpty(" ")       = false
     * StringUtils.isEmpty("bob")     = false
     * StringUtils.isEmpty("  bob  ") = false
     *
     * NOTE: This method changed in Lang version 2.0.
     * It no longer trims the String.
     * That functionality is available in isBlank().
     *
     * @param str  the String to check, may be null
     * @return `true` if the String is empty or null
     */
    @JvmStatic
    fun isEmpty(str: String?): Boolean {
        return str == null || str.isEmpty()
    }

    // IndexOf
    //-----------------------------------------------------------------------
    /**
     * Finds the first index within a String, handling `null`.
     * This method uses [String.indexOf].
     *
     * A `null` or empty ("") String will return `INDEX_NOT_FOUND (-1)`.
     *
     * StringUtils.indexOf(null, *)         = -1
     * StringUtils.indexOf("", *)           = -1
     * StringUtils.indexOf("aabaabaa", 'a') = 0
     * StringUtils.indexOf("aabaabaa", 'b') = 2
     *
     * @param str  the String to check, may be null
     * @param searchChar  the character to find
     * @return the first index of the search character,
     *  -1 if no match or `null` string input
     * @since 2.0
     */
    @JvmStatic
    @JvmOverloads
    fun indexOf(str: String?, searchChar: Char, startPos: Int = 0): Int {
        if (isEmpty(str)) {
            return -1
        }
        return str!!.indexOf(searchChar, startPos)
    }

    /**
     * Finds the first index within a String, handling `null`.
     * This method uses [String.indexOf].
     *
     * A `null` String will return `-1`.
     *
     * StringUtils.indexOf(null, *)          = -1
     * StringUtils.indexOf(*, null)          = -1
     * StringUtils.indexOf("", "")           = 0
     * StringUtils.indexOf("", *)            = -1 (except when * = "")
     * StringUtils.indexOf("aabaabaa", "a")  = 0
     * StringUtils.indexOf("aabaabaa", "b")  = 2
     * StringUtils.indexOf("aabaabaa", "ab") = 1
     * StringUtils.indexOf("aabaabaa", "")   = 0
     *
     * @param str  the String to check, may be null
     * @param searchStr  the String to find, may be null
     * @return the first index of the search String,
     *  -1 if no match or `null` string input
     * @since 2.0
     */
    @JvmStatic
    fun indexOf(str: String?, searchStr: String?): Int {
        if (str == null || searchStr == null) {
            return -1
        }
        return str.indexOf(searchStr)
    }

    /**
     * Index of sign charaters (i.e. '+' or '-').
     *
     * @param str The string to search
     * @param startPos The start position
     * @return the index of the first sign character or -1 if not found
     */
    private fun indexOfSignChars(str: String, startPos: Int): Int {
        var idx = indexOf(str, '+', startPos)
        if (idx < 0) {
            idx = indexOf(str, '-', startPos)
        }
        return idx
    }

    /**
     * Parses a string representing a date by trying a variety of different parsers.
     *
     * The parse will try each parse pattern in turn.
     * A parse is only deemed successful if it parses the whole of the input string.
     * If no parse patterns match, a ParseException is thrown.
     * The parser parses strictly - it does not allow for dates such as "February 942, 1996".
     *
     * @param str  the date to parse, not null
     * @param parsePatterns  the date format patterns to use, see SimpleDateFormat, not null
     * @return the parsed date
     * @throws IllegalArgumentException if the date string or pattern array is null
     * @throws java.text.ParseException if none of the date patterns were suitable
     * @since 2.5
     */
    @JvmStatic
    @Throws(ParseException::class)
    fun parseDateStrictly(str: String?, parsePatterns: Array<String>?): Date {
        return parseDateWithLeniency(str, parsePatterns, false)
    }

    /**
     * Parses a string representing a date by trying a variety of different parsers.
     *
     * The parse will try each parse pattern in turn.
     * A parse is only deemed successful if it parses the whole of the input string.
     * If no parse patterns match, a ParseException is thrown.
     *
     * @param str  the date to parse, not null
     * @param parsePatterns  the date format patterns to use, see SimpleDateFormat, not null
     * @param lenient Specify whether or not date/time parsing is to be lenient.
     * @return the parsed date
     * @throws IllegalArgumentException if the date string or pattern array is null
     * @throws ParseException if none of the date patterns were suitable
     */
    @Throws(ParseException::class)
    private fun parseDateWithLeniency(str: String?, parsePatterns: Array<String>?,
                                       lenient: Boolean): Date {
        if (str == null || parsePatterns == null) {
            throw IllegalArgumentException("Date and Patterns must not be null")
        }

        val parser = SimpleDateFormat()
        parser.isLenient = lenient
        val pos = ParsePosition(0)
        for (i in parsePatterns.indices) {

            var pattern = parsePatterns[i]

            // LANG-530 - need to make sure 'ZZ' output doesn't get passed to SimpleDateFormat
            if (parsePatterns[i].endsWith("ZZ")) {
                pattern = pattern.substring(0, pattern.length - 1)
            }

            parser.applyPattern(pattern)
            pos.index = 0

            var str2 = str!!
            // LANG-530 - need to make sure 'ZZ' output doesn't hit SimpleDateFormat as it will ParseException
            if (parsePatterns[i].endsWith("ZZ")) {
                var signIdx = indexOfSignChars(str2, 0)
                while (signIdx >= 0) {
                    str2 = reformatTimezone(str2, signIdx)
                    signIdx = indexOfSignChars(str2, ++signIdx)
                }
            }

            val date = parser.parse(str2, pos)
            if (date != null && pos.index == str2.length) {
                return date
            }
        }
        throw ParseException("Unable to parse the date: $str", -1)
    }
}
