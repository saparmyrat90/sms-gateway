package com.gateway.sms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** One row of the on-screen log. The SMS text is never stored, only the last 4 digits of the number. */
data class JobLogEntry(
    val id: Long,
    val phoneTail: String,
    val ok: Boolean,
    val error: String?,
    val at: Long,
)

data class GatewayStatus(
    val running: Boolean = false,
    val lastSmsAt: Long? = null,
    val lastError: String? = null,
    val sentCount: Int = 0,
    val log: List<JobLogEntry> = emptyList(),
)

class StatusStore {
    private val _status = MutableStateFlow(GatewayStatus())
    val status: StateFlow<GatewayStatus> = _status

    fun setRunning(running: Boolean) = _status.update { it.copy(running = running) }

    fun setError(message: String?) = _status.update { it.copy(lastError = message) }

    fun addJob(entry: JobLogEntry) = _status.update {
        it.copy(
            lastSmsAt = if (entry.ok) entry.at else it.lastSmsAt,
            sentCount = if (entry.ok) it.sentCount + 1 else it.sentCount,
            lastError = if (entry.ok) null else entry.error,
            log = (listOf(entry) + it.log).take(MAX_LOG),
        )
    }

    private companion object {
        const val MAX_LOG = 20
    }
}

object Gateway {
    val store = StatusStore()
}
