package com.kestrel.vpn.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base64CompatTest {
    @Test
    fun `decodes padded unpadded and url-safe input`() {
        assertEquals("hello?>", Base64Compat.decodeToString("aGVsbG8/Pg=="))
        assertEquals("hello?>", Base64Compat.decodeToString("aGVsbG8/Pg"))
        assertEquals("hello?>", Base64Compat.decodeToString("aGVsbG8_Pg"))
    }

    @Test
    fun `ignores whitespace and wrapped lines`() {
        assertEquals("hello?>", Base64Compat.decodeToString("aGVs\nbG8/\r\nPg=="))
    }

    @Test
    fun `rejects invalid input`() {
        assertNull(Base64Compat.decodeToString(""))
        assertNull(Base64Compat.decodeToString("a"))
        assertNull(Base64Compat.decodeToString("@@@@"))
    }
}
