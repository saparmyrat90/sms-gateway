package com.gateway.sms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeApi : GatewayApi {
    val queue = ArrayDeque<List<SmsJob>>()
    val reports = mutableListOf<Triple<Long, Boolean, String?>>()
    var claimError: ApiException? = null
    var reportFailures = 0
    var claims = 0
    val claimLimits = mutableListOf<Int>()

    override suspend fun claim(limit: Int): List<SmsJob> {
        claims++
        claimLimits += limit
        claimError?.let { throw it }
        return queue.removeFirstOrNull() ?: emptyList()
    }

    override suspend fun report(id: Long, ok: Boolean, error: String?): String {
        if (reportFailures > 0) {
            reportFailures--
            throw ApiException.Server(500)
        }
        reports += Triple(id, ok, error)
        return if (ok) "sent" else "failed"
    }
}

private class FakeSender(private val result: SendResult = SendResult(true)) : SmsSender {
    val sent = mutableListOf<Pair<String, String>>()
    override suspend fun send(phone: String, text: String, simSlot: Int): SendResult {
        sent += phone to text
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayLoopTest {
    private fun job(id: Long) = SmsJob(id, "+99365111111", "code $id")

    @Test
    fun claimSendReport_happyPath() = runTest {
        val api = FakeApi().apply { queue += listOf(job(1), job(2)) }
        val sender = FakeSender()
        val store = StatusStore()
        val loop = GatewayLoop(api, sender, store, { LoopConfig() }, { currentTime })
        backgroundScope.launch { loop.run() }
        runCurrent()

        assertEquals(listOf("code 1", "code 2"), sender.sent.map { it.second })
        assertEquals(listOf(Triple(1L, true, null), Triple(2L, true, null)), api.reports)
        assertEquals(2, store.status.value.sentCount)
        assertTrue(store.status.value.running)
    }

    @Test
    fun failedSend_isReportedWithError() = runTest {
        val api = FakeApi().apply { queue += listOf(job(7)) }
        val loop = GatewayLoop(api, FakeSender(SendResult(false, "no service")), StatusStore(), { LoopConfig() }, { currentTime })
        backgroundScope.launch { loop.run() }
        runCurrent()
        assertEquals(listOf(Triple(7L, false, "no service")), api.reports)
    }

    @Test
    fun emptyQueue_sleepsConfiguredIdleDelay() = runTest {
        val api = FakeApi()
        val loop = GatewayLoop(api, FakeSender(), StatusStore(), { LoopConfig(idleDelayMs = 3_000) }, { currentTime })
        backgroundScope.launch { loop.run() }
        runCurrent()
        assertEquals(1, api.claims)
        advanceTimeBy(2_999); runCurrent()
        assertEquals(1, api.claims)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, api.claims)
    }

    @Test
    fun errors_backOffExponentiallyUpTo30s() = runTest {
        val api = FakeApi().apply { claimError = ApiException.Network(java.io.IOException("down")) }
        val store = StatusStore()
        val loop = GatewayLoop(api, FakeSender(), store, { LoopConfig() }, { currentTime })
        backgroundScope.launch { loop.run() }
        runCurrent()                     // t=0   claim #1, wait 3 s
        advanceTimeBy(3_000); runCurrent() // t=3   claim #2, wait 6 s
        advanceTimeBy(6_000); runCurrent() // t=9   claim #3, wait 12 s
        advanceTimeBy(12_000); runCurrent() // t=21  claim #4, wait 24 s
        advanceTimeBy(24_000); runCurrent() // t=45  claim #5, wait 30 s (capped)
        assertEquals(5, api.claims)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(5, api.claims)
        advanceTimeBy(1); runCurrent()
        assertEquals(6, api.claims)
        assertTrue(store.status.value.lastError!!.contains("Network"))
    }

    @Test
    fun reportFailure_thenReclaim_doesNotSendTwice() = runTest {
        val api = FakeApi().apply {
            queue += listOf(job(5))
            queue += listOf(job(5)) // server hands the same job out again
            reportFailures = 3      // all three report attempts of the first round fail
        }
        val sender = FakeSender()
        val loop = GatewayLoop(api, sender, StatusStore(), { LoopConfig(idleDelayMs = 3_000) }, { currentTime })
        backgroundScope.launch { loop.run() }
        advanceTimeBy(10_000); runCurrent()

        assertEquals(1, sender.sent.size)
        assertEquals(listOf(Triple(5L, true, null)), api.reports)
    }

    @Test
    fun perMinuteCap_limitsClaimsAndWaits() = runTest {
        val api = FakeApi().apply { queue += listOf(job(1), job(2)) }
        val sender = FakeSender()
        val loop = GatewayLoop(api, sender, StatusStore(), { LoopConfig(maxPerMinute = 2, idleDelayMs = 1_000) }, { currentTime })
        backgroundScope.launch { loop.run() }
        runCurrent()
        assertEquals(2, sender.sent.size)
        assertEquals(listOf(2), api.claimLimits) // claim limited to remaining room

        api.queue += listOf(job(3))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, api.claims)              // still capped: no further claim in the window
        advanceTimeBy(31_000); runCurrent()
        assertEquals(3, sender.sent.size)        // window expired: job 3 goes out
    }

    @Test
    fun logNeverContainsMessageText() = runTest {
        val api = FakeApi().apply { queue += listOf(job(9)) }
        val store = StatusStore()
        val loop = GatewayLoop(api, FakeSender(), store, { LoopConfig() }, { currentTime })
        backgroundScope.launch { loop.run() }
        runCurrent()
        val entry = store.status.value.log.single()
        assertEquals("1111", entry.phoneTail)
        assertTrue(!entry.toString().contains("code 9"))
    }
}
