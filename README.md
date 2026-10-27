# SMS Gateway (Android)

An Android phone with a SIM sends OTP SMS for the Barber backend. The app pulls jobs from the backend
(`/api/v1/sms/gateway/claim`), sends each one with `SmsManager`, and reports the result after the
system confirms it (`/api/v1/sms/gateway/report`). Distributed as an APK only (Google Play restricts SMS permissions).

## Build

```bash
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest # API client (MockWebServer) + claim/send/report loop tests
```

For a release APK, create a keystore and add a `signingConfig` to `app/build.gradle.kts`, then run `:app:assembleRelease`.

## Install

1. Copy the APK to the phone (or `adb install -r app-debug.apk`) and allow installs from unknown sources.
2. Open the app, tap **Grant permissions** (SMS, phone state, notifications).
3. Fill in **Server URL** (HTTPS; plain HTTP is accepted only for localhost / emulator `10.0.2.2` / private LAN)
   and **Gateway key** (`SMS_GATEWAY_KEY` from the backend `.env`). The key is stored encrypted with an Android Keystore key.
4. Pick the SIM (dual-SIM phones show one chip per active SIM; "Default" uses the system choice).
5. Tap **Send test SMS** to check the SIM, then **Start**.

Settings: idle poll interval (default 3 s when the queue is empty; errors back off 3 s → 30 s),
max SMS per minute (default 30, a second guard on top of the server), start on boot.

## Phone checklist

- Disable battery optimization: tap **Ignore battery optimization** and allow it. On Xiaomi/Huawei/Oppo/Samsung also enable "autostart" / "never sleep" for the app.
- Lock the app in the recents screen so the system does not clean it up.
- Keep the phone on a charger with stable internet (Wi-Fi preferred).
- Use a SIM with an SMS package; check the balance regularly.
- Keep the persistent notification visible (it shows the last SMS time and the last error).
- Android may ask again for the "SMS" permission after OS updates; open the app once after an update.

## Behaviour notes

- A job is reported only after the system confirms `RESULT_OK` or an error code. If the report call fails, the SMS is not sent again when the server re-issues the job; only the report is retried.
- The foreground service uses type `specialUse` (not `dataSync`) because `dataSync` services are stopped after about 6 h per day on Android 15+.
- SMS text is never logged or shown; the on-screen log keeps the job id and the last 4 digits of the number.
- Messages longer than 160 chars are split with `divideMessage`.
