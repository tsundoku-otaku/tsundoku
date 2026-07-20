package mihon.core.archive

/**
 * URL scheme for local-novel images resolved through the app's page loaders rather than the
 * network. Declared here in core.archive because it is the lowest module shared by every
 * producer/consumer (EpubReader/EpubWriter here, source-local's asset rewriter, and the app's
 * WebView/text-view image loaders), so they can't drift on the literal.
 */
const val NOVEL_IMAGE_SCHEME = "tsundoku-novel-image://"
