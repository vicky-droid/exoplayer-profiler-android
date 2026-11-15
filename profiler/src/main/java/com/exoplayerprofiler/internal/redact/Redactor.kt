package com.exoplayerprofiler.internal.redact

import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Redactor for playback profiler data. Implements rules R1-R12 from the SDK plan.
 *
 * All functions are pure and have no Android dependencies, so they are fully
 * unit-testable on the JVM.
 */
internal object Redactor {

  // URI/ID patterns
  private val UUID_PATTERN =
    Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )
  private val HEX_24_PLUS_PATTERN = Pattern.compile("^[0-9a-fA-F]{24,}$")
  private val BASE64URL_24_PLUS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{24,}$")
  private val JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]*")
  private val BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+\\S+")

  // ---------------------------------------------------------------------------
  // R1: URL userinfo — Remove user:pass@
  // R2: Query string and fragment — Drop entirely
  // R3: Path tokens — Replace opaque ID path segments with <id>
  // R4: Host — Keep
  // ---------------------------------------------------------------------------
  fun redactUri(uri: String?): String {
    if (uri.isNullOrBlank()) return ""
    var u = uri
    // R2: drop fragment
    val fragmentIdx = u.indexOf('#')
    if (fragmentIdx >= 0) u = u.substring(0, fragmentIdx)
    // R2: drop query string
    val queryIdx = u.indexOf('?')
    if (queryIdx >= 0) u = u.substring(0, queryIdx)
    // R1: remove userinfo
    u = removeUserInfo(u)
    // R3: path tokens
    val schemeIdx = u.indexOf("://")
    if (schemeIdx >= 0) {
      val pathStart = u.indexOf('/', schemeIdx + 3)
      if (pathStart >= 0) {
        val hostPart = u.substring(0, pathStart)
        val pathPart = u.substring(pathStart)
        u = hostPart + redactPath(pathPart)
      }
    } else {
      u = redactPath(u)
    }
    // R6: tokens
    u = redactTokens(u)
    return u
  }

  private fun removeUserInfo(url: String): String {
    val schemeIdx = url.indexOf("://")
    if (schemeIdx < 0) return url
    val schemeEnd = schemeIdx + 3
    val rest = url.substring(schemeEnd)
    val atIdx = rest.indexOf('@')
    if (atIdx < 0) return url
    // Everything before @ is userinfo; drop it
    return url.substring(0, schemeEnd) + rest.substring(atIdx + 1)
  }

  // ---------------------------------------------------------------------------
  // R3: Path tokens — Replace opaque ID path segments with <id>
  // ---------------------------------------------------------------------------
  fun redactPath(path: String?): String {
    if (path.isNullOrBlank()) return path ?: ""
    val segments = path.split("/").toMutableList()
    for (i in segments.indices) {
      val seg = segments[i]
      if (isOpaqueId(seg)) {
        segments[i] = "<id>"
      }
    }
    return segments.joinToString("/")
  }

  private fun isOpaqueId(segment: String): Boolean {
    if (segment.isEmpty() || segment == "<id>") return false
    // Keep file names with extensions (e.g. asset_540_1_6_000000008.mp4, init.mp4, index.mpd)
    if (segment.contains('.')) return false
    // UUIDs
    if (UUID_PATTERN.matcher(segment).matches()) return true
    // Hex strings 24+ chars
    if (HEX_24_PLUS_PATTERN.matcher(segment).matches()) return true
    // Base64url strings 24+ chars
    if (BASE64URL_24_PLUS_PATTERN.matcher(segment).matches()) return true
    return false
  }

  // ---------------------------------------------------------------------------
  // R6: Bearer/JWT patterns — Replace with <token>
  // ---------------------------------------------------------------------------
  fun redactTokens(text: String?): String {
    if (text.isNullOrBlank()) return text ?: ""
    var result = JWT_PATTERN.matcher(text).replaceAll("<token>")
    result = BEARER_PATTERN.matcher(result).replaceAll("Bearer <token>")
    return result
  }

  // ---------------------------------------------------------------------------
  // R8: Content ID hash — sha256, first 12 hex
  // ---------------------------------------------------------------------------
  fun hashContentId(contentId: String?): String {
    if (contentId.isNullOrBlank()) return ""
    return sha256First12(contentId)
  }

  // ---------------------------------------------------------------------------
  // R12: URI hash — first 12 hex of sha256(raw URI)
  // ---------------------------------------------------------------------------
  fun hashUri(uri: String?): String {
    if (uri.isNullOrBlank()) return ""
    return sha256First12(uri)
  }

  private fun sha256First12(input: String): String {
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
      hash.joinToString("") { "%02x".format(it) }.substring(0, 12)
    } catch (_: Exception) {
      ""
    }
  }

  // ---------------------------------------------------------------------------
  // R5: Header allow-list — Keep only safe headers
  // ---------------------------------------------------------------------------
  private val ALLOWED_HEADERS =
    setOf("X-Cache", "Age", "CF-Cache-Status", "Content-Type", "Content-Length", "Accept-Ranges")

  fun redactHeaders(headers: Map<String, String>?): Map<String, String> {
    if (headers.isNullOrEmpty()) return emptyMap()
    val allowedLower = ALLOWED_HEADERS.map { it.lowercase() }.toSet()
    return headers.filter { (k, _) -> allowedLower.contains(k.lowercase()) }
  }

  // ---------------------------------------------------------------------------
  // R7: DRM — Never record license URLs, PSSH, init data, key IDs
  // ---------------------------------------------------------------------------
  fun redactDrmUrl(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return "<drm-license-url>"
  }

  // ---------------------------------------------------------------------------
  // R9: Error messages — Run R2/R3/R6 over message and stack lines
  // ---------------------------------------------------------------------------
  fun redactErrorMessage(message: String?): String {
    if (message.isNullOrBlank()) return message ?: ""
    val urlRegex = "(https?://[^\\s\"'>]+)".toRegex()
    var result = urlRegex.replace(message) { matchResult ->
      redactUri(matchResult.value)
    }
    result = redactTokens(result)
    result = UUID_PATTERN.matcher(result).replaceAll("<id>")
    return result
  }

  // ---------------------------------------------------------------------------
  // R10: Custom events — Run R6 over string values; drop sensitive keys
  // ---------------------------------------------------------------------------
  private val SENSITIVE_KEYS =
    setOf("token", "authorization", "password", "secret", "auth", "apikey", "api_key")

  fun isSensitiveKey(key: String?): Boolean {
    if (key.isNullOrBlank()) return false
    val lower = key.lowercase()
    return SENSITIVE_KEYS.any { lower.contains(it) }
  }

  fun redactCustomEventValue(key: String?, value: Any?): Any? {
    if (key.isNullOrBlank() || value == null) return value
    if (isSensitiveKey(key)) return "<redacted>"
    if (value is String) {
      var s = redactTokens(value)
      if (s.startsWith("http://") || s.startsWith("https://")) {
        s = redactUri(s)
      }
      return s
    }
    return value
  }
}
