package eu.kanade.tachiyomi.ui.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

/**
 * WebView-based login activity for trackers that use cookie authentication.
 * Used for NovelUpdates and NovelList trackers.
 */
class TrackerWebViewLoginActivity : BaseActivity() {

    private val trackerManager: TrackerManager by injectLazy()
    private val networkPreferences: NetworkPreferences by injectLazy()

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        super.onCreate(savedInstanceState)

        if (!WebViewUtil.supportsWebView(this)) {
            toast(MR.strings.information_webview_required, Toast.LENGTH_LONG)
            finish()
            return
        }

        val trackerId = intent.extras?.getLong(TRACKER_ID_KEY, -1L) ?: -1L
        val trackerName = intent.extras?.getString(TRACKER_NAME_KEY) ?: return
        val loginUrl = intent.extras?.getString(LOGIN_URL_KEY) ?: return
        val configuredUserAgent = networkPreferences.defaultUserAgent.get().trim().ifBlank { null }

        setContent {
            TachiyomiTheme {
                TrackerWebViewLoginScreen(
                    trackerId = trackerId,
                    trackerName = trackerName,
                    loginUrl = loginUrl,
                    configuredUserAgent = configuredUserAgent,
                    onLoginSuccess = { token ->
                        val tracker = when (trackerId) {
                            10L -> trackerManager.novelUpdates
                            11L -> trackerManager.novelList
                            12L -> trackerManager.ranobeDb
                            13L -> trackerManager.mangaBaka
                            else -> null
                        }
                        tracker?.let {
                            it.logout()
                            kotlinx.coroutines.runBlocking {
                                // Use "cookie_auth" as username since isLoggedIn requires non-empty username
                                it.login("cookie_auth", token)
                            }
                            toast("Login successful!")
                            setResult(RESULT_OK)
                            finish()
                        }
                    },
                    onNavigateUp = { finish() },
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    companion object {
        private const val TRACKER_ID_KEY = "tracker_id_key"
        private const val TRACKER_NAME_KEY = "tracker_name_key"
        private const val LOGIN_URL_KEY = "login_url_key"

        fun newIntent(context: Context, trackerId: Long, trackerName: String, loginUrl: String): Intent {
            return Intent(context, TrackerWebViewLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(TRACKER_ID_KEY, trackerId)
                putExtra(TRACKER_NAME_KEY, trackerName)
                putExtra(LOGIN_URL_KEY, loginUrl)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerWebViewLoginScreen(
    trackerId: Long,
    trackerName: String,
    loginUrl: String,
    configuredUserAgent: String?,
    onLoginSuccess: (String) -> Unit,
    onNavigateUp: () -> Unit,
) {
    var currentUrl by remember { mutableStateOf(loginUrl) }
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var manualTokenInput by remember { mutableStateOf("") }
    var showManualTokenDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val extractToken: () -> Unit = {
        scope.launch {
            val token = extractTokenFromCookies(trackerId, currentUrl)
            if (token != null) {
                onLoginSuccess(token)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Login to $trackerName") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                    if (trackerId == 11L || trackerId == 12L || trackerId == 13L) {
                        IconButton(onClick = { showManualTokenDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Enter token/cookie",
                            )
                        }
                    }
                    IconButton(onClick = extractToken) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "Complete Login",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        configuredUserAgent?.let { settings.userAgentString = it }

                        // Enable cookies
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                currentUrl = url ?: loginUrl
                                logcat(LogPriority.DEBUG) { "Page loaded: $url" }
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                            }
                        }

                        loadUrl(loginUrl)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Instructions card at the bottom
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                val instructions = when (trackerId) {
                    10L -> "Login to NovelUpdates, then tap the ✓ button to complete login."
                    11L -> "Login to NovelList, then tap the ✓ button to complete login. Use the edit icon to paste token/cookie manually."
                    12L -> "Login to RanobeDB, then tap the ✓ button to complete login. Use the edit icon to paste the auth_session cookie manually."
                    13L -> "Login to MangaBaka, navigate to your API keys page, then paste the PAT (mb-...) via the edit icon. The ✓ button also tries to extract a session cookie."
                    else -> "Login, then tap the ✓ button to complete."
                }
                Text(
                    text = instructions,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            if (showManualTokenDialog && (trackerId == 11L || trackerId == 12L || trackerId == 13L)) {
                AlertDialog(
                    onDismissRequest = { showManualTokenDialog = false },
                    title = {
                        Text(
                            when (trackerId) {
                                11L -> "NovelList token/cookie"
                                12L -> "RanobeDB cookie"
                                13L -> "MangaBaka PAT"
                                else -> "Token"
                            },
                        )
                    },
                    text = {
                        OutlinedTextField(
                            value = manualTokenInput,
                            onValueChange = { manualTokenInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Token or cookie") },
                            placeholder = {
                                val hint = when (trackerId) {
                                    11L -> "Paste JWT, novellist cookie, or full cookie header"
                                    12L -> "Paste auth_session value or full cookie header"
                                    13L -> "Paste your mb-... PAT from mangabaka.org account"
                                    else -> "Paste your token"
                                }
                                Text(hint)
                            },
                            singleLine = false,
                            maxLines = 4,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val token = when (trackerId) {
                                    11L -> normalizeNovelListToken(manualTokenInput)
                                    12L -> normalizeRanobeDbCookie(manualTokenInput)
                                    13L -> normalizeMangaBakaToken(manualTokenInput)
                                    else -> null
                                }
                                if (token != null) {
                                    showManualTokenDialog = false
                                    onLoginSuccess(token)
                                } else {
                                    context.toast("Could not extract auth value from input")
                                }
                            },
                        ) {
                            Text("Use")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showManualTokenDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}

private fun normalizeMangaBakaToken(input: String): String? {
    val raw = input.trim()
    if (raw.isEmpty()) return null
    // Accept a bare `mb-...` PAT, a `Bearer mb-...` header, or any cookie blob containing one.
    val patMatch = Regex("mb-[A-Za-z0-9_\\-]+").find(raw)
    if (patMatch != null) return patMatch.value
    return raw.removePrefix("Bearer ").trim().ifBlank { null }
}

private fun normalizeRanobeDbCookie(input: String): String? {
    val raw = input.trim()
    if (raw.isEmpty()) return null
    val match = Regex("(?:^|[;\\s])auth_session=([^;]+)").find(raw)
    val value = match?.groupValues?.getOrNull(1) ?: raw.removePrefix("auth_session=")
    return value.trim().ifBlank { null }?.let { "auth_session=$it" }
}

private fun normalizeNovelListToken(input: String): String? {
    val raw = input.trim()
    if (raw.isEmpty()) return null

    val extracted = run {
        val cookieRegex = Regex("(?:^|[;\\s])novellist=([^;]+)")
        val match = cookieRegex.find(raw)
        match?.groupValues?.getOrNull(1) ?: raw
    }

    val candidate = extracted.trim().removePrefix("novellist=")

    // If a direct JWT/token was pasted, use it as-is.
    if (!candidate.startsWith("base64-")) {
        val tokenInJson = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
            .find(candidate)
            ?.groupValues
            ?.getOrNull(1)
        return tokenInJson ?: candidate.ifBlank { null }
    }

    val decoded = runCatching {
        val encoded = candidate.removePrefix("base64-")
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        String(bytes, Charsets.UTF_8)
    }.getOrNull() ?: return null

    return Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
        .find(decoded)
        ?.groupValues
        ?.getOrNull(1)
}

private suspend fun extractTokenFromCookies(trackerId: Long, currentUrl: String): String? {
    return withContext(Dispatchers.Main) {
        val cookieManager = CookieManager.getInstance()

        when (trackerId) {
            // NovelUpdates - extract session cookies
            10L -> {
                val cookies = cookieManager.getCookie("https://www.novelupdates.com")
                logcat(LogPriority.DEBUG) { "NovelUpdates cookies: $cookies" }
                if (cookies != null && cookies.contains("wordpress_logged_in")) {
                    // Return all cookies as the token
                    cookies
                } else {
                    logcat(LogPriority.WARN) { "NovelUpdates login cookie not found" }
                    null
                }
            }
            // NovelList - extract JWT from cookie
            11L -> {
                val cookies = cookieManager.getCookie("https://www.novellist.co")
                logcat(LogPriority.DEBUG) { "NovelList cookies: $cookies" }
                if (cookies != null) {
                    // Try to find the novellist cookie which contains the JWT
                    val regex = Regex("novellist=([^;]+)")
                    val match = regex.find(cookies)
                    val novellistCookie = match?.groupValues?.get(1)

                    if (novellistCookie != null) {
                        // Decode base64 if needed
                        val decoded = try {
                            if (novellistCookie.startsWith("base64-")) {
                                val base64Part = novellistCookie.removePrefix("base64-")
                                val decoded = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT)
                                String(decoded)
                            } else {
                                novellistCookie
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Failed to decode NovelList cookie" }
                            novellistCookie
                        }

                        // Extract access_token from JSON if present
                        try {
                            val jsonRegex = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
                            val tokenMatch = jsonRegex.find(decoded)
                            tokenMatch?.groupValues?.get(1) ?: decoded
                        } catch (e: Exception) {
                            decoded
                        }
                    } else {
                        logcat(LogPriority.WARN) { "NovelList cookie not found" }
                        null
                    }
                } else {
                    null
                }
            }
            // MangaBaka - prefer Better-Auth session cookies; fall back to PAT pattern.
            13L -> {
                val cookies = cookieManager.getCookie("https://mangabaka.org")
                logcat(LogPriority.DEBUG) { "MangaBaka cookies: $cookies" }
                if (cookies != null) {
                    val sessionData = Regex("__Secure-better-auth\\.session_data=([^;]+)").find(cookies)?.groupValues?.get(1)
                    val sessionToken = Regex("__Secure-better-auth\\.session_token=([^;]+)").find(cookies)?.groupValues?.get(1)
                    when {
                        sessionData != null || sessionToken != null -> buildString {
                            if (sessionData != null) append("__Secure-better-auth.session_data=$sessionData")
                            if (sessionData != null && sessionToken != null) append("; ")
                            if (sessionToken != null) append("__Secure-better-auth.session_token=$sessionToken")
                        }
                        else -> Regex("mb-[A-Za-z0-9_\\-]+").find(cookies)?.value
                    }
                } else {
                    null
                }
            }
            // RanobeDB - extract auth_session cookie
            12L -> {
                val cookies = cookieManager.getCookie("https://ranobedb.org")
                logcat(LogPriority.DEBUG) { "RanobeDB cookies: $cookies" }
                if (cookies != null) {
                    val regex = Regex("auth_session=([^;]+)")
                    val match = regex.find(cookies)
                    val authSession = match?.groupValues?.get(1)
                    if (authSession != null) {
                        "auth_session=$authSession"
                    } else {
                        logcat(LogPriority.WARN) { "RanobeDB auth_session cookie not found" }
                        null
                    }
                } else {
                    null
                }
            }
            else -> null
        }
    }
}
