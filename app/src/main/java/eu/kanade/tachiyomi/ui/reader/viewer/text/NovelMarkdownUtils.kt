package eu.kanade.tachiyomi.ui.reader.viewer.text

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

object NovelMarkdownUtils {
    private val frontmatterTitleRegex = Regex("^title\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val markdownFlavour = GFMFlavourDescriptor()

    fun isMarkdownUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val normalized = url.substringBefore('#').lowercase()
        return normalized.endsWith(".md") || normalized.endsWith(".markdown")
    }

    fun toHtml(markdown: String): String {
        val normalized = markdown.replace("\r\n", "\n").replace("\r", "\n")
        val (frontmatterTitle, body) = extractFrontmatter(normalized)
        val renderedBody = runCatching {
            val tree = MarkdownParser(markdownFlavour).buildMarkdownTreeFromString(body)
            HtmlGenerator(body, tree, markdownFlavour).generateHtml().trim()
        }.getOrElse {
            simpleFallbackHtml(body)
        }

        if (frontmatterTitle.isNullOrBlank()) {
            return renderedBody
        }

        return buildString {
            append("<h1>")
            append(escapeHtml(frontmatterTitle))
            append("</h1>\n")
            append(renderedBody)
        }.trim()
    }

    private fun extractFrontmatter(markdown: String): Pair<String?, String> {
        val lines = markdown.lines()
        if (lines.isEmpty() || lines.first().trim() != "---") {
            return null to markdown
        }

        var closingIndex = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                closingIndex = i
                break
            }
        }
        if (closingIndex == -1) {
            return null to markdown
        }

        val title = lines
            .subList(1, closingIndex)
            .firstNotNullOfOrNull { line ->
                frontmatterTitleRegex.matchEntire(line.trim())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.trim('"', '\'')
            }

        val body = lines.drop(closingIndex + 1).joinToString("\n")
        return title to body
    }

    private fun simpleFallbackHtml(markdown: String): String {
        val escaped = escapeHtml(markdown).trim()
        if (escaped.isEmpty()) return ""

        return escaped
            .lines()
            .joinToString(separator = "<br />\n") { line ->
                if (line.isBlank()) "" else "<p>$line</p>"
            }
            .ifBlank { "<p>$escaped</p>" }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
