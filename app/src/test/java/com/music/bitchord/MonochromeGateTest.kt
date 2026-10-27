package com.music.bitchord

import com.music.bitchord.data.sources.MonochromeGate
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The circuit breaker in front of Monochrome's files, on a clock the tests
 * move by hand, against a real HTTP server that counts what reaches it.
 */
class MonochromeGateTest {

    private lateinit var server: MockWebServer
    private var clock = 1_000_000L
    private val gate = MonochromeGate { clock }
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gate.watch(server.hostName)
        client = OkHttpClient.Builder().addInterceptor(gate).build()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun get(path: String = "/track/1"): okhttp3.Response =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().also { it.close() }

    private fun answer(code: Int) = server.enqueue(MockResponse().setResponseCode(code))

    @Test
    fun `a 429 opens the circuit and the next file is refused without a request`() {
        answer(429)

        assertEquals(429, get().code)
        val refused = get()

        assertEquals(429, refused.code)
        assertNotNull(refused.header(MonochromeGate.SYNTHETIC_HEADER))
        assertEquals(1, server.requestCount)
        assertEquals(MonochromeGate.BASE_WINDOW_MS, gate.remainingMs())
    }

    @Test
    fun `the search is never gated`() {
        answer(429)
        get()
        answer(200)

        assertEquals(200, get("/search/tracks?q=x").code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `after the window one trial goes through and its success closes the circuit`() {
        answer(429)
        get()
        clock += MonochromeGate.BASE_WINDOW_MS
        answer(206)
        answer(206)

        assertEquals(206, get().code)
        assertEquals(206, get().code)
        assertEquals(3, server.requestCount)
        assertEquals(0L, gate.remainingMs())
    }

    @Test
    fun `only one trial is let through while it is still in flight`() {
        answer(429)
        get()
        clock += MonochromeGate.BASE_WINDOW_MS
        server.enqueue(MockResponse().setResponseCode(206).setHeadersDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))

        val trial = Thread { get() }.apply { start() }
        Thread.sleep(100)
        val second = get()
        trial.join()

        assertNotNull(second.header(MonochromeGate.SYNTHETIC_HEADER))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a refused trial reopens the circuit for twice as long`() {
        answer(429)
        get()
        clock += MonochromeGate.BASE_WINDOW_MS
        answer(429)

        get()

        assertEquals(MonochromeGate.BASE_WINDOW_MS * 2, gate.remainingMs())
    }

    @Test
    fun `the window doubles up to a ceiling`() {
        assertEquals(90_000L, MonochromeGate.windowMs(1))
        assertEquals(180_000L, MonochromeGate.windowMs(2))
        assertEquals(MonochromeGate.MAX_WINDOW_MS, MonochromeGate.windowMs(9))
    }

    @Test
    fun `a failed trial that wasn't a refusal lets the next request try`() {
        answer(429)
        get()
        clock += MonochromeGate.BASE_WINDOW_MS
        answer(502)
        answer(206)

        assertEquals(502, get().code)
        assertEquals(206, get().code)
        assertEquals(3, server.requestCount)
    }

    /** Stragglers from the burst that tripped it don't count as further refusals. */
    @Test
    fun `a 429 while already open doesn't lengthen the window`() {
        // The slow one is asked first and answers last, after the quick one
        // has already opened the circuit.
        server.enqueue(MockResponse().setResponseCode(429).setHeadersDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        answer(429)

        val straggler = Thread { get() }.apply { start() }
        Thread.sleep(100)
        get()
        straggler.join()

        assertEquals(2, server.requestCount)
        assertEquals(MonochromeGate.BASE_WINDOW_MS, gate.remainingMs())
    }

    @Test
    fun `a refusal is reported to a lookup that started before it`() {
        val before = clock
        assertFalse(gate.refusedSince(before))
        answer(429)

        get()

        assertTrue(gate.refusedSince(before))
        assertFalse(gate.refusedSince(clock + 1))
    }

    @Test
    fun `turning a wait-able lookup away counts as a refusal`() {
        assertFalse(gate.turnsAway())
        answer(429)
        get()
        clock += 1
        val asked = clock

        assertTrue(gate.turnsAway())
        assertTrue(gate.refusedSince(asked))
    }

    @Test
    fun `another host is never touched`() {
        val other = MonochromeGate { clock }
        assertNull(other.takeIf { it.gates(server.url("/track/1")) })
    }
}
