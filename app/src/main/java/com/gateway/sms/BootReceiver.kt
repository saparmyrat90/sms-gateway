package com.gateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = SettingsStore(context).load()
        if (s.enabled && s.startOnBoot) GatewayService.start(context)
    }
}
