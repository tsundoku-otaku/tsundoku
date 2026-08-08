package mihon.core.archive

import java.net.URLEncoder

const val NOVEL_IMAGE_SCHEME = "tsundoku-novel-image://"

// "%20" instead of URLEncoder's "+", since android.net.Uri.decode and java.net.URLDecoder disagree on "+".
fun novelImageUrl(path: String): String =
    NOVEL_IMAGE_SCHEME + URLEncoder.encode(path, "UTF-8").replace("+", "%20")
