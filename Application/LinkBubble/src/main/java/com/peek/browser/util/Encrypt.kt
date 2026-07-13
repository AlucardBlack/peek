/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.peek.browser.util

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec

// via https://gist.github.com/aogilvie/6267013#file-string_encrypt_decrypt-md
object Encrypt {

    private const val TAG = "Encrypt"

    private const val sCryptoPass = "Bl3rGBYl3rG66"

    @JvmStatic
    fun encryptIt(value: String): String {
        try {
            val keySpec = DESKeySpec(sCryptoPass.toByteArray(charset("UTF8")))
            val keyFactory = SecretKeyFactory.getInstance("DES")
            val key = keyFactory.generateSecret(keySpec)

            val clearText = value.toByteArray(charset("UTF8"))
            // Cipher is not thread safe
            val cipher = Cipher.getInstance("DES")
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val encrypedValue = Base64.encodeToString(cipher.doFinal(clearText), Base64.DEFAULT)
            Log.d(TAG, "Encrypted: $value -> $encrypedValue")
            return encrypedValue

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return value
    }

    @JvmStatic
    fun decryptIt(value: String): String {
        try {
            val keySpec = DESKeySpec(sCryptoPass.toByteArray(charset("UTF8")))
            val keyFactory = SecretKeyFactory.getInstance("DES")
            val key = keyFactory.generateSecret(keySpec)

            val encrypedPwdBytes = Base64.decode(value, Base64.DEFAULT)
            // cipher is not thread safe
            val cipher = Cipher.getInstance("DES")
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decrypedValueBytes = cipher.doFinal(encrypedPwdBytes)

            val decrypedValue = String(decrypedValueBytes)
            Log.d(TAG, "Decrypted: $value -> $decrypedValue")
            return decrypedValue

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return value
    }
}
