package com.gateway.sms

import kotlinx.coroutines.delay

data class LoopConfig(
    val idleDelayMs: Long = 3_000,
    val maxPerMinute: Int = 30,
    val simSlot: Int = -1,
)

/** Sliding one-minute window: a second guard against runaway sending. */
class RateLimiter(private val now: () -> Long) {
    private val sent = ArrayDeque<Long>()

    fun available(maxPerMinute: Int): Int {
        val cutoff = now() - 60_000
        while (sent.isNotEmpty() && sent.first() <= cutoff) sent.removeFirst()
        return (maxPerMinute - sent.size).coerceAtLeast(0)
    }

    fun record() = sent.addLast(now())
}

/**
 * claim -> send -> report. A job whose SMS was already sent but whose report failed is kept in
 * [unreported]; if the server hands it out again we only re-report, never re-send.
 */
class GatewayLoop(
    private val api: GatewayApi,
    private val sender: SmsSender,
    private val store: StatusStore,
    private val config: () -> LoopConfig,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val unreported = LinkedHashMap<Long, SendResult>()
    private val limiter = RateLimiter(now)

    suspend fun run() {
        var backoff = MIN_BACKOFF_MS
        store.setRunning(true)
        try {
            while (true) {
                val cfg = config()
                val room = limiter.available(cfg.maxPerMinute)
                if (room == 0) {
                    store.setError("Per-minute send cap (${cfg.maxPerMinute}) reached, waiting")
                    delay(RATE_WAIT_MS)
                    continue
                }
                val jobs = try {
                    api.claim(minOf(CLAIM_BATCH, room))
                } catch (e: ApiException) {
                    store.setError(e.message)
                    delay(backoff)
                    backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
                    continue
                }
                backoff = MIN_BACKOFF_MS
                if (jobs.isEmpty()) {
                    store.setError(null)
                    delay(cfg.idleDelayMs)
                    continue
                }
                for (job in jobs) process(job, cfg)
            }
        } finally {
            store.setRunning(false)
        }
    }

    private suspend fun process(job: SmsJob, cfg: LoopConfig) {
        val result = unreported[job.id] ?: sender.send(job.phone, job.text, cfg.simSlot).also {
            if (it.ok) limiter.record()
            store.addJob(JobLogEntry(job.id, job.phone.takeLast(4), it.ok, it.error, now()))
        }
        if (!reportWithRetry(job.id, result)) {
            unreported[job.id] = result
            while (unreported.size > MAX_UNREPORTED) unreported.remove(unreported.keys.first())
        } else {
            unreported.remove(job.id)
        }
    }

    private suspend fun reportWithRetry(id: Long, result: SendResult): Boolean {
        var wait = 1_000L
        repeat(REPORT_ATTEMPTS) { attempt ->
            try {
                api.report(id, result.ok, result.error)
                return true
            } catch (e: ApiException) {
                store.setError(e.message)
                if (attempt < REPORT_ATTEMPTS - 1) {
                    delay(wait)
                    wait *= 2
                }
            }
        }
        return false
    }

    private companion object {
        const val CLAIM_BATCH = 5
        const val MIN_BACKOFF_MS = 3_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val RATE_WAIT_MS = 2_000L
        const val REPORT_ATTEMPTS = 3
        const val MAX_UNREPORTED = 200
    }
}
