package eu.kanade.tachiyomi.ui.reader.viewer.text.webview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class NovelWebViewInlineFeedback(
    private val scope: CoroutineScope,
    private val evaluateJs: (String) -> Unit,
) {

    fun showInlineLoading(isPrepend: Boolean) {
        val js = """
            (function() {
                var loadingDiv = document.getElementById('$ID_INLINE_LOADING');
                if (!loadingDiv) {
                    loadingDiv = document.createElement('div');
                    loadingDiv.id = '$ID_INLINE_LOADING';
                    loadingDiv.style.textAlign = 'center';
                    loadingDiv.style.padding = '20px';
                    loadingDiv.style.color = '#888';
                    loadingDiv.innerHTML = 'Loading...';
                }

                if ($isPrepend) {
                    document.body.insertBefore(loadingDiv, document.body.firstChild);
                } else {
                    document.body.appendChild(loadingDiv);
                }
            })();
        """.trimIndent()
        evaluateJs(js)
    }

    fun hideInlineLoading(@Suppress("UNUSED_PARAMETER") isPrepend: Boolean = false) {
        val js = """
            (function() {
                var loadingDiv = document.getElementById('$ID_INLINE_LOADING');
                if (loadingDiv) loadingDiv.remove();
            })();
        """.trimIndent()
        evaluateJs(js)
    }

    fun showInlineError(message: String, isPrepend: Boolean) {
        scope.launch(Dispatchers.Main) {
            val escapedMessage = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")

            val js = """
                (function() {
                    var errorDiv = document.getElementById('$ID_INLINE_ERROR');
                    if (errorDiv) errorDiv.remove();
                    errorDiv = document.createElement('div');
                    errorDiv.id = '$ID_INLINE_ERROR';
                    errorDiv.style.textAlign = 'center';
                    errorDiv.style.padding = '16px';
                    errorDiv.style.color = '#FF5252';
                    errorDiv.style.backgroundColor = 'rgba(255, 82, 82, 0.1)';
                    errorDiv.style.cursor = 'pointer';
                    errorDiv.innerHTML = '$escapedMessage (tap to dismiss)';
                    errorDiv.onclick = function() { errorDiv.remove(); };

                    if ($isPrepend) {
                        document.body.insertBefore(errorDiv, document.body.firstChild);
                    } else {
                        document.body.appendChild(errorDiv);
                    }
                })();
            """.trimIndent()
            evaluateJs(js)

            delay(AUTO_DISMISS_MS)
            evaluateJs("document.getElementById('$ID_INLINE_ERROR')?.remove();")
        }
    }

    companion object {
        const val ID_INLINE_LOADING = "inline-loading"
        const val ID_INLINE_ERROR = "inline-error"
        private const val AUTO_DISMISS_MS = 8_000L
    }
}
