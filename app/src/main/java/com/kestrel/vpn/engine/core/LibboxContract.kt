package com.kestrel.vpn.engine.core

/**
 * The libbox bridge is reflection-based, so a renamed class/method or changed signature in a new
 * sing-box release would otherwise only fail at connect time with an obscure error. This contract
 * lists exactly what the bridge calls; [verify] reports every missing piece up-front.
 */
object LibboxContract {

    /** A method looked up by name AND exact parameter types (as `Class.getName()` strings). */
    data class Signature(val method: String, val parameterTypes: List<String>) {
        override fun toString() = "$method(${parameterTypes.joinToString(",")})"
    }

    data class Requirement(
        val className: String,
        val methods: List<String>,
        val signatures: List<Signature> = emptyList(),
        val needsNoArgConstructor: Boolean = false
    )

    private const val PKG = "io.nekohasekai.libbox."

    val REQUIREMENTS: List<Requirement> = listOf(
        Requirement(
            className = PKG + "Libbox",
            methods = listOf("setup", "newService"),
            signatures = listOf(
                Signature("setup", listOf(PKG + "SetupOptions")),
                Signature("newService", listOf("java.lang.String", PKG + "PlatformInterface"))
            )
        ),
        Requirement(
            className = PKG + "SetupOptions",
            methods = listOf("setBasePath", "setWorkingPath", "setTempPath"),
            signatures = listOf(
                Signature("setBasePath", listOf("java.lang.String")),
                Signature("setWorkingPath", listOf("java.lang.String")),
                Signature("setTempPath", listOf("java.lang.String"))
            ),
            needsNoArgConstructor = true
        ),
        Requirement(
            className = PKG + "PlatformInterface",
            methods = listOf("openTun", "autoDetectInterfaceControl", "usePlatformAutoDetectInterfaceControl")
        )
    )

    /** Returns human-readable problems; empty list means the bridge is compatible. */
    fun verify(
        requirements: List<Requirement> = REQUIREMENTS,
        load: (String) -> Class<*>? = { name -> runCatching { Class.forName(name) }.getOrNull() }
    ): List<String> {
        val problems = mutableListOf<String>()
        for (req in requirements) {
            val clazz = load(req.className)
            if (clazz == null) {
                problems += "missing class ${req.className}"
                continue
            }
            val all = clazz.methods + clazz.declaredMethods
            val available = all.map { it.name }.toSet()
            req.methods.filter { it !in available }.forEach {
                problems += "missing method ${req.className}#$it"
            }
            for (sig in req.signatures) {
                if (sig.method !in available) continue // already reported as a missing method
                val matches = all.any { m ->
                    m.name == sig.method && m.parameterTypes.map { it.name } == sig.parameterTypes
                }
                if (!matches) problems += "incompatible signature ${req.className}#$sig"
            }
            if (req.needsNoArgConstructor && clazz.constructors.none { it.parameterCount == 0 }) {
                problems += "missing no-arg constructor ${req.className}"
            }
        }
        return problems
    }
}
