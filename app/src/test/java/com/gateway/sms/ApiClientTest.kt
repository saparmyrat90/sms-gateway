package com.gateway.sms

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpGatewayApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = OkHttpGatewayApi(server.url("/").toString(), "secret")
    }

    @After
    fun tearDown() = server.close()

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun claim_parsesItems_andSendsKeyAndLimit() = runBlocking {
        server.enqueue(json(200, """{"items":[{"id":12,"phone":"+99365111111","text":"code 4821"}]}"""))
        val jobs = api.claim(5)
        assertEquals(listOf(SmsJob(12, "+99365111111", "code 4821")), jobs)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/sms/gateway/claim?limit=5", req.target)
        assertEquals("secret", req.headers["X-Gateway-Key"])
    }

    @Test
    fun report_sendsBodyAndReturnsStatus() = runBlocking {
        server.enqueue(json(200, """{"id":12,"status":"failed"}"""))
        assertEquals("failed", api.report(12, false, "no service"))
        val req = server.takeRequest()
        assertEquals("/api/v1/sms/gateway/report", req.target)
        val body = req.body!!.utf8()
        assertTrue(body, body.contains(""""id":12""") && body.contains(""""ok":false""") && body.contains("no service"))
    }

    @Test
    fun report_truncatesErrorTo200() = runBlocking {
        server.enqueue(json(200, """{"id":1,"status":"failed"}"""))
        api.report(1, false, "x".repeat(500))
        assertEquals(200, "x+".toRegex().find(server.takeRequest().body!!.utf8())!!.value.length)
    }

    @Test
    fun report_okOmitsError() = runBlocking {
        server.enqueue(json(200, """{"id":1,"status":"sent"}"""))
        api.report(1, true, null)
        assertTrue(!server.takeRequest().body!!.utf8().contains("error"))
    }

    @Test
    fun status401_isUnauthorized() {
        server.enqueue(json(401, "{}"))
        assertThrows(ApiException.Unauthorized::class.java) { runBlocking { api.claim(1) } }
    }

    @Test
    fun status503_isGatewayDisabled() {
        server.enqueue(json(503, "{}"))
        assertThrows(ApiException.GatewayDisabled::class.java) { runBlocking { api.claim(1) } }
    }

    @Test
    fun status500_isServerError() {
        server.enqueue(json(500, "{}"))
        val e = assertThrows(ApiException.Server::class.java) { runBlocking { api.claim(1) } }
        assertEquals(500, e.code)
    }

    @Test
    fun garbageBody_isBadResponse() {
        server.enqueue(json(200, "not json"))
        assertThrows(ApiException.BadResponse::class.java) { runBlocking { api.claim(1) } }
    }

    @Test
    fun connectionRefused_isNetworkError() {
        val dead = OkHttpGatewayApi("http://127.0.0.1:1/", "k")
        assertThrows(ApiException.Network::class.java) { runBlocking { dead.claim(1) } }
    }

    @Test
    fun urlPolicy_requiresHttpsExceptLocal() {
        assertNull(UrlPolicy.check("https://api.example.com"))
        assertNull(UrlPolicy.check("http://localhost:8000"))
        assertNull(UrlPolicy.check("http://10.0.2.2:8000"))
        assertNull(UrlPolicy.check("http://192.168.1.5:8000"))
        assertNotNull(UrlPolicy.check("http://example.com"))
        assertNotNull(UrlPolicy.check("http://8.8.8.8"))
        assertNotNull(UrlPolicy.check("not a url"))
    }

    @Test
    fun httpToPublicHost_isRejectedAtConstruction() {
        assertThrows(ApiException.BadConfig::class.java) { OkHttpGatewayApi("http://example.com", "k") }
    }
}
