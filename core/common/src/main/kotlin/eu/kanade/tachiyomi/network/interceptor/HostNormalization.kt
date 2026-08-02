package eu.kanade.tachiyomi.network.interceptor

/**
 * Normalizes a host for rate-limit bookkeeping so "www.example.com" and "example.com" are
 * treated as the same target. A source's declared baseUrl and the hosts its actual requests
 * land on aren't always consistent about the "www." prefix, and treating them as distinct hosts
 * would split traffic across two untracked windows instead of pacing it together.
 */
fun String.normalizedRateLimitHost(): String = lowercase().removePrefix("www.")

/**
 * Public suffixes where a plain last-two-labels split would land on the suffix itself rather
 * than a real registrable domain - either a wildcard hosting provider, where every tenant gets
 * their own subdomain of a *shared* two-label suffix (e.g. "alice.github.io" and
 * "bob.github.io" are unrelated sites, not the same registrable domain), or a multi-part ccTLD
 * suffix (e.g. "example.co.uk", where the registrable domain is three labels, not "co.uk").
 * Not exhaustive - this is a pragmatic list of hosts plausible for self-hosted novel/scraper
 * sources and translation endpoints to land on, not a full Public Suffix List. See
 * [topPrivateDomainOrNull] for why a full PSL lookup isn't used instead.
 */
private val MULTI_TENANT_SUFFIXES = setOf(
    "github.io", "gitlab.io", "netlify.app", "vercel.app", "pages.dev",
    "herokuapp.com", "web.app", "firebaseapp.com", "appspot.com",
    "blogspot.com", "wordpress.com", "workers.dev", "onrender.com",
    "surge.sh", "glitch.me", "repl.co", "ngrok.io", "ngrok-free.app",
    "co.uk", "org.uk", "gov.uk", "co.jp", "com.au", "com.br",
)

/**
 * Matches a dotted-quad IPv4 literal (octet range isn't validated - "999.999.999.999" still
 * matches - since this only needs to tell "IP-shaped" from "domain-shaped", not validate IPs).
 */
private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * A best-effort approximation of this host's registrable domain - e.g. "api.example.com" and
 * "cdn.example.com" both resolve to "example.com" - or null if the host doesn't have enough
 * labels to derive one (a bare TLD, localhost, an IP literal, etc). Deliberately not OkHttp's
 * [okhttp3.HttpUrl.topPrivateDomain]: that needs its bundled PublicSuffixDatabase.list resource
 * on the classpath, which isn't reliably present in every module that ends up calling this (it
 * threw `IllegalStateException: Unable to load PublicSuffixDatabase.list resource.` in :domain's
 * plain-JUnit unit tests). A plain last-two-labels split is wrong whenever those two labels are
 * themselves a [MULTI_TENANT_SUFFIXES] entry - take one more label in that case. Beyond that
 * known list, a false-positive match still just paces an unrelated host alongside a source that
 * happens to share those two labels - still throttled, just maybe grouped with the wrong
 * source's window. Erring toward "still gets paced" is an acceptable tradeoff for not depending
 * on a resource file.
 *
 * IPv4/IPv6 literals are excluded up front rather than falling through to the label split: a
 * dotted-quad IP's last two octets aren't a registrable domain, so two unrelated self-hosted
 * servers whose IPs happen to share their last two octets (plausible on any home LAN - e.g.
 * 192.168.1.50 and 10.0.1.50 both reduce to "1.50") would otherwise be wrongly grouped as the
 * same "domain" and share a rate-limit spec that was never meant to apply to both.
 */
fun String.topPrivateDomainOrNull(): String? {
    val normalized = lowercase().removePrefix("www.")
    if (normalized.contains(':') || IPV4_LITERAL.matches(normalized)) return null
    val labels = normalized.split('.')
    if (labels.size < 2) return null
    val lastTwo = labels.takeLast(2).joinToString(".")
    if (lastTwo !in MULTI_TENANT_SUFFIXES) return lastTwo
    if (labels.size < 3) return null
    return labels.takeLast(3).joinToString(".")
}
