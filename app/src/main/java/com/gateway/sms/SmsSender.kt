package com.gateway.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

data class SendResult(val ok: Boolean, val error: String? = null)

data class SimInfo(val slot: Int, val label: String)

interface SmsSender {
    /** Suspends until the system confirms the SMS was sent (or failed). Never throws except on cancellation. */
    suspend fun send(phone: String, text: String, simSlot: Int): SendResult
}

class AndroidSmsSender(context: Context) : SmsSender {
    private val context = context.applicationContext
    private val counter = AtomicInteger(0)

    override suspend fun send(phone: String, text: String, simSlot: Int): SendResult {
        if (!PHONE.matches(phone)) return SendResult(false, "bad phone number")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SendResult(false, "SEND_SMS permission not granted")
        }
        return withTimeoutOrNull(SENT_TIMEOUT_MS) { sendAndAwait(phone, text, simSlot) }
            ?: SendResult(false, "timeout waiting for sent confirmation")
    }

    private suspend fun sendAndAwait(phone: String, text: String, simSlot: Int): SendResult =
        suspendCancellableCoroutine { cont ->
            val manager = try {
                smsManager(simSlot)
            } catch (e: Exception) {
                cont.resume(SendResult(false, e.message ?: "SIM error"))
                return@suspendCancellableCoroutine
            }

            val action = "$ACTION_PREFIX${counter.incrementAndGet()}"
            val parts = manager.divideMessage(text)
            val remaining = AtomicInteger(parts.size)
            val firstError = AtomicReference<String?>(null)
            val done = AtomicBoolean(false)

            lateinit var receiver: BroadcastReceiver
            fun finish(result: SendResult?) {
                if (!done.compareAndSet(false, true)) return
                runCatching { context.unregisterReceiver(receiver) }
                if (result != null && cont.isActive) cont.resume(result)
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (resultCode != Activity.RESULT_OK) firstError.compareAndSet(null, describe(resultCode))
                    if (remaining.decrementAndGet() == 0) {
                        val err = firstError.get()
                        finish(if (err == null) SendResult(true) else SendResult(false, err))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { finish(null) }

            try {
                val sentIntents = parts.map {
                    PendingIntent.getBroadcast(
                        context,
                        counter.incrementAndGet(),
                        Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }
                if (parts.size == 1) {
                    manager.sendTextMessage(phone, null, text, sentIntents[0], null)
                } else {
                    manager.sendMultipartTextMessage(phone, null, parts, ArrayList(sentIntents), null)
                }
            } catch (e: Exception) {
                finish(SendResult(false, e.message ?: e.javaClass.simpleName))
            }
        }

    @Suppress("DEPRECATION")
    private fun smsManager(simSlot: Int): SmsManager {
        val default = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        if (simSlot < 0) return default
        val subId = subscriptionIdForSlot(simSlot)
            ?: throw IllegalStateException("SIM slot ${simSlot + 1} is not active")
        return if (Build.VERSION.SDK_INT >= 31) {
            default.createForSubscriptionId(subId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        }
    }

    private fun subscriptionIdForSlot(slot: Int): Int? = try {
        context.getSystemService(SubscriptionManager::class.java)
            .activeSubscriptionInfoList
            ?.firstOrNull { it.simSlotIndex == slot }
            ?.subscriptionId
    } catch (e: SecurityException) {
        throw IllegalStateException("READ_PHONE_STATE permission not granted")
    }

    /** Active SIMs, for the settings dropdown. Empty when READ_PHONE_STATE is missing. */
    fun availableSims(): List<SimInfo> = try {
        context.getSystemService(SubscriptionManager::class.java)
            .activeSubscriptionInfoList.orEmpty()
            .sortedBy { it.simSlotIndex }
            .map { SimInfo(it.simSlotIndex, "SIM ${it.simSlotIndex + 1} (${it.displayName})") }
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null pdu"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "sms limit exceeded"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short code not allowed"
        else -> "error code $code"
    }

    private companion object {
        val PHONE = Regex("""^\+?\d{6,15}$""")
        const val SENT_TIMEOUT_MS = 45_000L // server re-issues a claimed job after 60 s
        const val ACTION_PREFIX = "com.gateway.sms.SENT."
    }
}
