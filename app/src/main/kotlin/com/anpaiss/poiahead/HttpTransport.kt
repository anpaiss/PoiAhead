package com.anpaiss.poiahead

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A way to POST a form body and get the response back, blocking.
 *
 * Two of them exist because the Karoo has two very different network paths:
 * its own WiFi (only when tethered or at home) and the phone's link over BT,
 * which only the SDK channel can use. The SDK channel caps responses at
 * [OnHttpResponse.MAX_REQUEST_SIZE], so a transport declares its cap and the
 * caller tiles the query to stay under it.
 */
interface HttpTransport {
    val name: String
    /** Largest response body this transport can deliver, or null if unbounded. */
    val maxResponseBytes: Int?
    /** @throws IOException on any network, transport or HTTP-status failure. */
    fun post(url: String, formBody: String, timeoutMs: Int): ByteArray
}

private const val USER_AGENT = "PoiAhead/0.1 (Karoo extension)"

/** Plain HttpURLConnection: works only when the Karoo itself has connectivity. */
class DirectTransport : HttpTransport {
    override val name = "direct"
    override val maxResponseBytes: Int? = null

    override fun post(url: String, formBody: String, timeoutMs: Int): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "POST"
            connectTimeout = timeoutMs
            readTimeout    = timeoutMs
            doOutput       = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            conn.outputStream.use { it.write(formBody.toByteArray()) }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * The Karoo's own HTTP channel: WiFi when connected, otherwise the companion
 * app over BT. Bridged to a blocking call so [PoiRepository] keeps its simple
 * synchronous contract; the consumer is always removed, timeout or not.
 *
 * `waitForConnection` is false on purpose: with no link the request fails fast
 * and the caller falls back to the direct transport and then to the cache, and
 * the periodic coverage check retries later. Queued requests would otherwise
 * pile up behind a `fetchInFlight` lock for an unbounded time.
 */
class SdkTransport(private val karoo: KarooSystemService) : HttpTransport {
    override val name = "sdk"
    override val maxResponseBytes: Int = OnHttpResponse.MAX_REQUEST_SIZE

    override fun post(url: String, formBody: String, timeoutMs: Int): ByteArray {
        val done = CountDownLatch(1)
        var complete: HttpResponseState.Complete? = null
        var failure: String? = null

        val id = karoo.addConsumer(
            OnHttpResponse.MakeHttpRequest(
                method  = "POST",
                url     = url,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent"   to USER_AGENT,
                ),
                body = formBody.toByteArray(),
                waitForConnection = false,
            ),
            { msg -> failure = msg; done.countDown() },
            { done.countDown() },
        ) { event: OnHttpResponse ->
            (event.state as? HttpResponseState.Complete)?.let {
                complete = it
                done.countDown()
            }
        }
        try {
            if (!done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) throw IOException("sdk: timeout")
        } finally {
            karoo.removeConsumer(id)
        }

        val r = complete ?: throw IOException("sdk: ${failure ?: "no response"}")
        r.error?.let { throw IOException("sdk: $it") }
        if (r.statusCode != 200) throw IOException("HTTP ${r.statusCode}")
        return r.body ?: throw IOException("sdk: empty body")
    }
}
