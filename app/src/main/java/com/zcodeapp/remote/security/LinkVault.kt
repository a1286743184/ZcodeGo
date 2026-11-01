package com.zcodeapp.remote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 链接加密（v2 6.2）：远控 URL 含 sid/mid，视为秘密；AndroidKeyStore AES/GCM 封装，
 * 密钥不可导出。密钥失效时调用方应丢弃密文并要求重新配对，不做明文回退。
 */
object LinkVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "zrcode_link_key"
    private const val IV_LEN = 12

    fun encrypt(plain: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain)
        val out = ByteArray(IV_LEN + ct.size)
        System.arraycopy(cipher.iv, 0, out, 0, IV_LEN)
        System.arraycopy(ct, 0, out, IV_LEN, ct.size)
        Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(encoded: String): ByteArray? = runCatching {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        if (all.size <= IV_LEN) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, IV_LEN)))
        cipher.doFinal(all.copyOfRange(IV_LEN, all.size))
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
