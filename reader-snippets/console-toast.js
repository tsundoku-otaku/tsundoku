// Console -> toast bridge check. Confirms the devtools WebChromeClient is attached on this build.
// Requires Advanced settings: novelWebViewDevTools ON + novelConsoleErrorToast ON.
// The client toasts LOG/WARNING/ERROR (capped 120 chars).
(function() {
    var stamp = (performance && performance.now) ? Math.round(performance.now()) : 0;
    console.log('Tsundoku devtools: LOG ok @' + stamp);
    console.warn('Tsundoku devtools: WARN ok');
    console.error('Tsundoku devtools: ERROR ok');
    // WebView maps console.info to MessageLevel.LOG, so INFO also toasts; console.debug maps to
    // DEBUG, which the client's gate skips.
    console.debug('Tsundoku devtools: DEBUG (not toasted)');
    console.info('Tsundoku devtools: INFO (toasts, maps to LOG)');
})();
