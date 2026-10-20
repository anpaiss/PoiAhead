package com.anpaiss.poiahead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The on-disk cache: dedup by id, 30-day TTL, and the transport fallback it relies on. */
class PoiRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeTransport(private val bodies: Iterator<String>) : HttpTransport {
        override val name = "fake"
        override val maxResponseBytes: Int? = null
        var calls = 0
        override fun post(url: String, formBody: String, timeoutMs: Int): ByteArray {
            calls++
            if (!bodies.hasNext()) throw IOException("fake: nothing left for $url")
            return bodies.next().toByteArray()
        }
    }

    private fun overpass(vararg nodes: Pair<Long, String?>): String =
        """{"elements":[""" + nodes.joinToString(",") { (id, name) ->
            val tags = if (name != null) ""","tags":{"name":"$name"}""" else ""
            """{"type":"node","id":$id,"lat":45.0,"lon":9.0$tags}"""
        } + "]}"

    private fun repo(vararg bodies: String, file: File = File(tmp.root, "poi_cache.json")) =
        PoiRepository(file, null, listOf(FakeTransport(bodies.iterator())))

    private val bboxA = doubleArrayOf(45.0, 9.0, 45.1, 9.1)
    private val bboxB = doubleArrayOf(45.05, 9.05, 45.15, 9.15)

    @Test
    fun `the same node from two fetches is one POI`() {
        val r = repo(overpass(1L to "Fontana", 2L to null), overpass(1L to "Fontana", 3L to null))
        assertTrue(r.ensureCovered(bboxA))
        assertTrue(r.ensureCovered(bboxB))
        assertEquals(listOf("1", "2", "3"), r.allPois().map { it.id })
    }

    @Test
    fun `a covered area is served from cache without a fetch`() {
        val t = FakeTransport(listOf(overpass(1L to null)).iterator())
        val r = PoiRepository(File(tmp.root, "poi_cache.json"), null, listOf(t))
        r.ensureCovered(bboxA)
        r.ensureCovered(doubleArrayOf(45.02, 9.02, 45.08, 9.08))   // inside bboxA
        assertEquals(1, t.calls)
    }

    @Test
    fun `the cache survives a restart`() {
        val file = File(tmp.root, "poi_cache.json")
        repo(overpass(1L to "Fontana", 2L to null), file = file).ensureCovered(bboxA)
        val again = repo(file = file)
        assertTrue(again.isCovered(bboxA))
        assertEquals(mapOf("1" to "Fontana", "2" to null), again.allPois().associate { it.id to it.name })
        assertFalse(File(tmp.root, "poi_cache.json.tmp").exists())
    }

    @Test
    fun `an area older than 30 days is not covered`() {
        val file = File(tmp.root, "poi_cache.json")
        val day = 24 * 3600 * 1000L
        fun area(ts: Long) = """{"s":45.0,"w":9.0,"n":45.1,"e":9.1,"ts":$ts}"""
        val now = System.currentTimeMillis()
        file.writeText("""{"areas":[${area(now - 31 * day)}],"pois":[]}""")
        assertFalse(repo(file = file).isCovered(bboxA))
        file.writeText("""{"areas":[${area(now - 29 * day)}],"pois":[]}""")
        assertTrue(repo(file = file).isCovered(bboxA))
    }

    @Test
    fun `a non-JSON body is an IOException so the next server and transport are tried`() {
        val html = FakeTransport(listOf("<html>Too many requests</html>").iterator())
        val good = FakeTransport(listOf(overpass(7L to null)).iterator())
        val pois = OverpassClient.fetchDrinkingWater(bboxA, listOf(html, good))
        assertEquals(listOf("7"), pois.map { it.id })
        assertEquals(OverpassClient.ENDPOINTS.size, html.calls)   // every server of the first transport
        assertEquals(1, good.calls)
    }

    @Test
    fun `the second server answers when the first is down`() {
        val servers = ArrayList<String>()
        val t = object : HttpTransport {
            override val name = "fake"
            override val maxResponseBytes: Int? = null
            override fun post(url: String, formBody: String, timeoutMs: Int): ByteArray {
                servers += url
                if (url == OverpassClient.ENDPOINTS[0]) throw IOException("HTTP 504")
                return overpass(9L to null).toByteArray()
            }
        }
        assertEquals(listOf("9"), OverpassClient.fetchDrinkingWater(bboxA, listOf(t)).map { it.id })
        assertEquals(OverpassClient.ENDPOINTS.take(2), servers)
    }

    @Test(expected = IOException::class)
    fun `every transport failing is an IOException`() {
        OverpassClient.fetchDrinkingWater(bboxA, listOf(FakeTransport(listOf("not json").iterator())))
    }

    @Test
    fun `refresh downloads a covered area again and reports the count for that area`() {
        val t = FakeTransport(listOf(overpass(), overpass(1L to null, 2L to null)).iterator())
        val r = PoiRepository(File(tmp.root, "poi_cache.json"), null, listOf(t))
        assertTrue(r.ensureCovered(bboxA))          // an empty fill, like a Switzerland-only server
        assertEquals(0, r.lastFetched)
        assertTrue(r.ensureCovered(bboxA))          // covered: no fetch
        assertEquals(1, t.calls)
        assertTrue(r.refresh(bboxA))                // the button: fetched again
        assertEquals(2, t.calls)
        assertEquals(2, r.lastFetched)
        assertEquals(listOf("1", "2"), r.allPois().map { it.id })
    }

    @Test
    fun `a failure keeps one reason per server and knows when the Karoo is offline`() {
        val dns = object : HttpTransport {
            override val name = "sdk"
            override val maxResponseBytes: Int? = 100_000
            override fun post(url: String, formBody: String, timeoutMs: Int): ByteArray =
                throw IOException("sdk: Unable to resolve host \"${OverpassClient.hostOf(url)}\": No address associated with hostname")
        }
        val r = PoiRepository(File(tmp.root, "poi_cache.json"), null, listOf(dns, dns))
        assertFalse(r.ensureCovered(bboxA))
        val f = r.lastFailure!!
        assertTrue(f.offline)
        assertEquals(OverpassClient.ENDPOINTS.map { OverpassClient.hostOf(it) }, f.failures.keys.toList())
        assertEquals("overpass-api.de: no network", f.lines().lines().first())

        val busy = object : HttpTransport {
            override val name = "direct"
            override val maxResponseBytes: Int? = null
            override fun post(url: String, formBody: String, timeoutMs: Int): ByteArray = when (OverpassClient.hostOf(url)) {
                "overpass-api.de" -> throw IOException("HTTP 502")
                "z.overpass-api.de" -> throw IOException("sdk: timeout")
                else -> throw IOException("Failed to connect to ${OverpassClient.hostOf(url)}/1.2.3.4:443")
            }
        }
        val r2 = PoiRepository(File(tmp.root, "poi_cache2.json"), null, listOf(busy))
        assertFalse(r2.ensureCovered(bboxA))
        val g = r2.lastFailure!!
        assertFalse(g.offline)
        assertEquals(
            listOf("overpass-api.de: HTTP 502, server busy", "lz4.overpass-api.de: unreachable",
                   "z.overpass-api.de: no answer in 60 s", "overpass.private.coffee: unreachable"),
            g.lines().lines(),
        )
    }
}
