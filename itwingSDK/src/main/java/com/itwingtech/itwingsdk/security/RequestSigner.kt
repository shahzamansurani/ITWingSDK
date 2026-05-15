package com.itwingtech.itwingsdk.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RequestSigner(private val apiKey: String) {
    fun sign(method: String, path: String, timestamp: String, nonce: String, bodyHash: String): String {
        val payload = listOf(method.uppercase(), path, timestamp, nonce, bodyHash).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}



