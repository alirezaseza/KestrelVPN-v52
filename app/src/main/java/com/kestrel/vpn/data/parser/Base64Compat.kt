package com.kestrel.vpn.data.parser
object Base64Compat {
    private val decodeTable = IntArray(256) { -1 }.apply {
        for (i in 0 until 26) {
            this['A'.code + i] = i
            this['a'.code + i] = 26 + i
        }
        for (i in 0 until 10) {
            this['0'.code + i] = 52 + i
        }
        this['+'.code] = 62
        this['-'.code] = 62
        this['/'.code] = 63
        this['_'.code] = 63
    }
    fun decodeToBytes(input: String): ByteArray? {
        val cleaned = StringBuilder(input.length)
        for (ch in input) {
            if (!ch.isWhitespace()) {
                cleaned.append(ch)
            }
        }
        if (cleaned.isEmpty()) return null
        while (cleaned.isNotEmpty() && cleaned[cleaned.length - 1] == '=') {
            cleaned.setLength(cleaned.length - 1)
        }
        val len = cleaned.length
        if (len == 0 || len % 4 == 1) return null
        val outLen = (len * 3) / 4
        val output = ByteArray(outLen)
        var outIndex = 0
        var buffer = 0
        var bitsCollected = 0
        for (i in 0 until len) {
            val code = cleaned[i].code
            if (code >= 256) return null
            val value = decodeTable[code]
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                output[outIndex++] = ((buffer ushr bitsCollected) and 0xFF).toByte()
            }
        }
        return if (outIndex == outLen) output else output.copyOf(outIndex)
    }
    fun decodeToString(input: String): String? =
    decodeToBytes(input)?.toString(Charsets.UTF_8)
}
