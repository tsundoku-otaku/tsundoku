package mihon.telemetry

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

object TelemetryConfig {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        // To stop forks/test builds from polluting our data
        if (!context.isTsundokuProductionApp()) return

        analytics = FirebaseAnalytics.getInstance(context)
        FirebaseApp.initializeApp(context)
        crashlytics = FirebaseCrashlytics.getInstance()
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    private fun Context.isTsundokuProductionApp(): Boolean {
        if (packageName !in TSUNDOKU_PACKAGES) return false

        return packageManager.getPackageInfo(packageName, SignatureFlags)
            .getCertificateFingerprints()
            .any { it == TSUNDOKU_CERTIFICATE_FINGERPRINT }
    }
}

private val TSUNDOKU_PACKAGES = hashSetOf("app.tsundoku", "app.tsundoku.debug")
private const val TSUNDOKU_CERTIFICATE_FINGERPRINT =
    "9A:DD:65:5A:78:E9:6C:4E:C7:A5:3E:F8:9D:CC:B5:57:CB:5D:76:74:89:FA:C5:E7:85:D6:71:A5:A7:5D:4D:A2"
