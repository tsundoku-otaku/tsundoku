package tsundoku.telemetry

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object TelemetryConfig {
    private const val TAG: String = "Telemetry"
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        // To stop forks/test builds from polluting our data
        if (!context.isTsundokuProductionApp()) return

        // Check if Google Play Services is available before initializing Firebase
        if (!isGooglePlayServicesAvailable(context)) {
            logcat(LogPriority.WARN) { "Google Play Services not available, skipping Firebase initialization" }
            return
        }

        try {
            analytics = FirebaseAnalytics.getInstance(context)
            FirebaseApp.initializeApp(context)
            crashlytics = FirebaseCrashlytics.getInstance()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to initialize Firebase" }
        }
    }

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        return try {
            val availability = GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(context)
            resultCode == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Unable to check Google Play Services availability" }
            false
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    private fun Context.isTsundokuProductionApp(): Boolean {
        Log.d(TAG, "Checking packageName: $packageName")
        if (packageName !in TSUNDOKU_PACKAGES) return false

        Log.d(TAG, "Checking Signature")
        return packageManager.getPackageInfo(packageName, SignatureFlags)
            .getCertificateFingerprints()
            .any { it == TSUNDOKU_CERTIFICATE_FINGERPRINT }
    }
}

private val TSUNDOKU_PACKAGES = hashSetOf("app.tsundoku", "app.tsundoku.debug")
private const val TSUNDOKU_CERTIFICATE_FINGERPRINT =
    "CA:56:7A:E5:AA:C0:B4:9C:01:41:BF:CE:59:97:07:1E:1B:66:C3:17:54:B5:81:99:F0:A9:9B:45:42:8A:48:F1"
