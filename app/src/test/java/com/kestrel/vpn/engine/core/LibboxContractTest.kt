package com.kestrel.vpn.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibboxContractTest {

    @Test
    fun `reports missing class`() {
        val problems = LibboxContract.verify(
            requirements = listOf(LibboxContract.Requirement("a.b.Missing", listOf("x"))),
            load = { null }
        )
        assertEquals(listOf("missing class a.b.Missing"), problems)
    }

    @Test
    fun `reports missing method but accepts present ones`() {
        val problems = LibboxContract.verify(
            requirements = listOf(
                LibboxContract.Requirement("java.lang.String", listOf("length", "noSuchMethod"))
            ),
            load = { Class.forName(it) }
        )
        assertEquals(listOf("missing method java.lang.String#noSuchMethod"), problems)
    }

    @Test
    fun `real contract lists every class the bridge touches`() {
        val names = LibboxContract.REQUIREMENTS.map { it.className }
        assertTrue("io.nekohasekai.libbox.Libbox" in names)
        assertTrue("io.nekohasekai.libbox.PlatformInterface" in names)
    }

    @Test
    fun `accepts a matching signature and rejects a different parameter list`() {
        val ok = LibboxContract.Signature("substring", listOf("int", "int"))
        val wrong = LibboxContract.Signature("substring", listOf("long"))
        val problems = LibboxContract.verify(
            requirements = listOf(
                LibboxContract.Requirement("java.lang.String", listOf("substring"), listOf(ok, wrong))
            ),
            load = { Class.forName(it) }
        )
        assertEquals(listOf("incompatible signature java.lang.String#substring(long)"), problems)
    }

    @Test
    fun `reports a missing no-arg constructor`() {
        val problems = LibboxContract.verify(
            requirements = listOf(
                LibboxContract.Requirement("java.lang.Integer", emptyList(), needsNoArgConstructor = true),
                LibboxContract.Requirement("java.lang.StringBuilder", emptyList(), needsNoArgConstructor = true)
            ),
            load = { Class.forName(it) }
        )
        assertEquals(listOf("missing no-arg constructor java.lang.Integer"), problems)
    }

    @Test
    fun `real contract pins the exact entry-point signatures the bridge reflects on`() {
        val libbox = LibboxContract.REQUIREMENTS.first { it.className.endsWith(".Libbox") }
        assertTrue(libbox.signatures.any { it.method == "newService" && it.parameterTypes.size == 2 })
        assertTrue(libbox.signatures.any { it.method == "setup" && it.parameterTypes.size == 1 })
    }
}
