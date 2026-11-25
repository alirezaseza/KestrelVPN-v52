package com.kestrel.vpn.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory, privacy-safe event ring buffer for field debugging. Never persisted or uploaded;
 * the user can copy it from Settings. IPs, UUIDs and long secrets are redacted on write.
 */
object DiagnosticsLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private var previousSession: String = ""
    private val ipv4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val ipv6 = Regex("""\b([0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\b""")
    private val uuid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val longToken = Regex("""[A-Za-z0-9+/_=-]{32,}""")

    fun redact(text: String): String = text
        .replace(uuid, "<uuid>")
        .replace(ipv4, "<ip>")
        .replace(ipv6, "<ip6>")
        .replace(longToken, "<secret>")

    /**
     * Mirrors the log to a small file so the events right before a crash (even a native one that kills the
     * process instantly) can be read after the next start. Local only, same redaction as the in-memory log.
     */
    @Synchronized
    fun attachFile(target: File) {
        file = target
        previousSession = runCatching { target.readText().lines().takeLast(120).joinToString("\n") }.getOrDefault("")
        runCatching { target.writeText("") }
    }

    @Synchronized
    fun add(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$ts [$tag] ${redact(message)}"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        file?.let { f -> runCatching { f.appendText(line + "\n") } }
    }

    @Synchronized
    fun dump(): String {
        val cur = lines.joinToString("\n")
        return if (previousSession.isBlank()) cur else "--- previous session (last lines before it ended) ---\n$previousSession\n--- this session ---\n$cur"
    }

    @Synchronized
    fun clear() = lines.clear()
}
