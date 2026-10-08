/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.security.Key
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Stores the local Gateway client token encrypted by a non-exportable Android Keystore key. */
class GatewayTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): String {
        val encrypted = preferences.getString(TOKEN, null)
        if (encrypted != null) {
            runCatching { decrypt(Base64.decode(encrypted, Base64.NO_WRAP)) }
                .getOrNull()?.takeIf { it.length >= MINIMUM_TOKEN_LENGTH }?.let { return it }
        }
        val tokenBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(tokenBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encryptedBytes = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(TOKEN, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)).commit()) {
            "could not persist the local Gateway token"
        }
        return token
    }

    private fun decrypt(value: ByteArray): String {
        require(value.size > GCM_IV_BYTES) { "invalid encrypted Gateway token" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, value.copyOfRange(0, GCM_IV_BYTES)))
        return cipher.doFinal(value.copyOfRange(GCM_IV_BYTES, value.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it }
        return KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "pair-gateway-auth"
        const val TOKEN = "encrypted-client-token"
        const val KEY_ALIAS = "pair-gateway-client-token"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MINIMUM_TOKEN_LENGTH = 32
    }
}
