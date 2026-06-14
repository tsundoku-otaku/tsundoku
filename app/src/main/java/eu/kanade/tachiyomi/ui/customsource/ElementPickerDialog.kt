package eu.kanade.tachiyomi.ui.customsource

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.WebView
import com.kevinnzou.web.rememberWebViewNavigator
import com.kevinnzou.web.rememberWebViewState
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Lightweight, single-element WebView picker used by the manual source editor: open it next to a
 * selector field, navigate to the right page, tap an element, refine via the shared confirm dialog,
 * and the chosen CSS selector is returned through [onPicked]. Reuses the wizard's element-selector
 * JavaScript and confirm dialog so behaviour matches the guided flow.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementPickerDialog(
    initialUrl: String,
    label: String,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var webView by remember { mutableStateOf<WebView?>(null) }
        var selectionMode by remember { mutableStateOf(false) }
        var currentUrl by remember { mutableStateOf(initialUrl) }
        var urlField by remember { mutableStateOf(initialUrl) }
        var lastSelected by remember { mutableStateOf<SelectedElement?>(null) }
        var showConfirm by remember { mutableStateOf(false) }

        val webViewState = rememberWebViewState(url = initialUrl)
        val navigator = rememberWebViewNavigator()

        fun quote(s: String) = org.json.JSONObject.quote(s)
        fun highlight(sel: String) =
            webView?.evaluateJavascript("window.highlightElements(${quote(sel)});", null)
        fun clearHighlights() = webView?.evaluateJavascript("window.clearHighlights();", null)
        fun setSelection(enabled: Boolean) {
            webView?.evaluateJavascript("window.enableSelectionMode($enabled);", null)
            selectionMode = enabled
        }

        fun resolveRelatives(sel: String, cb: (String, List<Pair<String, String>>) -> Unit) {
            val wv = webView ?: return cb("", emptyList())
            wv.evaluateJavascript("window.relatives(${quote(sel)});") { result ->
                val parsed = runCatching {
                    val json = org.json.JSONObject(result?.trim('"').orEmpty().replace("\\\"", "\"").replace("\\\\", "\\"))
                    val parent = json.optString("parent", "")
                    val kids = json.optJSONArray("children")
                    val list = buildList {
                        if (kids != null) {
                            for (i in 0 until kids.length()) {
                                val c = kids.getJSONObject(i)
                                add(c.optString("label", "") to c.optString("selector", ""))
                            }
                        }
                    }
                    parent to list
                }.getOrDefault("" to emptyList())
                cb(parsed.first, parsed.second)
            }
        }

        val jsInterface = remember {
            ElementSelectorJSInterface(
                onElementSelected = { selector, html, text, parentSelectorsJson, href ->
                    val parents = runCatching {
                        val json = org.json.JSONObject(parentSelectorsJson)
                        buildMap { json.keys().forEach { k -> if (!json.isNull(k)) put(k, json.getString(k)) } }
                    }.getOrDefault(emptyMap())
                    lastSelected = SelectedElement(selector, html, text, parents, href)
                    showConfirm = true
                },
                onSelectionModeChanged = { selectionMode = it },
            )
        }

        val webClient = remember {
            object : AccompanistWebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    url?.let { currentUrl = it; urlField = it }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let { currentUrl = it; urlField = it }
                    view.evaluateJavascript(ELEMENT_SELECTOR_JS, null)
                    view.evaluateJavascript("window.enableSelectionMode($selectionMode);", null)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    if (u.startsWith("http")) { view?.loadUrl(u); return true }
                    return false
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(label) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { setSelection(!selectionMode) },
                    containerColor = if (selectionMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    icon = {
                        Icon(
                            if (selectionMode) Icons.Filled.TouchApp else Icons.Filled.Edit,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(
                            if (selectionMode) {
                                stringResource(TDMR.strings.selector_selection_on)
                            } else {
                                stringResource(TDMR.strings.selector_select_element)
                            },
                        )
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = urlField,
                        onValueChange = { urlField = it },
                        label = { Text(stringResource(TDMR.strings.custom_source_url_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { navigator.loadUrl(urlField) }) {
                        Icon(Icons.Filled.TouchApp, contentDescription = null)
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    WebView(
                        state = webViewState,
                        navigator = navigator,
                        modifier = Modifier.fillMaxSize(),
                        onCreated = { wv ->
                            webView = wv
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            wv.addJavascriptInterface(jsInterface, "AndroidSelector")
                        },
                        client = webClient,
                    )
                }
            }
        }

        if (showConfirm && lastSelected != null) {
            SelectorConfirmDialog(
                element = lastSelected!!,
                onResolveRelatives = ::resolveRelatives,
                onHighlight = { sel -> clearHighlights(); highlight(sel) },
                onConfirm = { selector ->
                    showConfirm = false
                    lastSelected = null
                    clearHighlights()
                    onPicked(selector)
                    onDismiss()
                },
                onDismiss = {
                    showConfirm = false
                    lastSelected = null
                    clearHighlights()
                },
            )
        }
    }
}
