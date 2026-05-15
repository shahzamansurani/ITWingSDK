package com.itwingtech.itwingsdk.analytics

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.itwingtech.itwingsdk.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLDecoder

class InstallReferrerReporter(private val context: Context, private val repository: ConfigRepository) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun collect() {
        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
                    client.endConnection()
                    return
                }

                val details = runCatching { client.installReferrer }.getOrNull()
                client.endConnection()
                if (details != null) {
                    submit(details)
                }
            }

            override fun onInstallReferrerServiceDisconnected() = Unit
        })
    }

    private fun submit(details: ReferrerDetails) {
        val referrer = details.installReferrer ?: return
        val params = parse(referrer)
        scope.launch {
            runCatching {
                repository.submitAttribution(
                    mapOf(
                        "install_referrer" to referrer,
                        "utm_source" to params["utm_source"],
                        "utm_medium" to params["utm_medium"],
                        "utm_campaign" to params["utm_campaign"],
                        "utm_term" to params["utm_term"],
                        "utm_content" to params["utm_content"],
                        "gclid" to params["gclid"],
                    ),
                )
            }
        }
    }

    private fun parse(referrer: String): Map<String, String> =
        referrer.split('&')
            .mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size != 2) null else URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            .toMap()
}
