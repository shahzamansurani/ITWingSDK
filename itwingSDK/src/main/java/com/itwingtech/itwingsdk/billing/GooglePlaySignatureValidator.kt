package com.itwingtech.itwingsdk.billing

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal object GooglePlaySignatureValidator {
    fun verify(base64PublicKey: String?, signedData: String?, purchaseSignature: String?): Boolean? {
        val key = base64PublicKey?.replace("\\s".toRegex(), "")?.takeIf { it.isNotBlank() } ?: return null
        val data = signedData?.takeIf { it.isNotBlank() } ?: return null
        val signature = purchaseSignature?.takeIf { it.isNotBlank() } ?: return null

        return runCatching {
            val publicKey = generatePublicKey(key)
            val signatureBytes = Base64.decode(signature, Base64.DEFAULT)
            listOf("SHA1withRSA", "SHA256withRSA").any { algorithm ->
                runCatching {
                    Signature.getInstance(algorithm).apply {
                        initVerify(publicKey)
                        update(data.toByteArray(Charsets.UTF_8))
                    }.verify(signatureBytes)
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    private fun generatePublicKey(base64PublicKey: String): PublicKey {
        val decodedKey = Base64.decode(base64PublicKey, Base64.DEFAULT)
        val keyFactory = KeyFactory.getInstance("RSA")
        return keyFactory.generatePublic(X509EncodedKeySpec(decodedKey))
    }
}
