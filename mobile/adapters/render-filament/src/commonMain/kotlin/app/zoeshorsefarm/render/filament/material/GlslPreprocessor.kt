package app.zoeshorsefarm.render.filament.material

/**
 * Resolves `#ifdef`, `#ifndef`, `#else` and `#endif` for a set of names, before the code goes to
 * Filament. The shader variants of this app (low coat, plain coat) are decided here, in code that is
 * unit-tested, instead of in whichever preprocessor the shader compiler happens to run first.
 * Other lines, including `#define`, pass through.
 */
object GlslPreprocessor {
    fun resolve(
        source: String,
        defines: Set<String>,
    ): String {
        val out = ArrayList<String>()
        // one entry per open #if: whether its current branch is active, and whether all outer ones are
        val active = ArrayList<Boolean>()
        for (line in source.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#ifdef ") -> {
                    active += (nameOf(trimmed) in defines) && enclosing(active)
                }

                trimmed.startsWith("#ifndef ") -> {
                    active += (nameOf(trimmed) !in defines) && enclosing(active)
                }

                trimmed == "#else" -> {
                    require(active.isNotEmpty()) { "#else without #ifdef" }
                    val outer = enclosing(active.subList(0, active.size - 1))
                    active[active.size - 1] = outer && !active[active.size - 1]
                }

                trimmed == "#endif" -> {
                    require(active.isNotEmpty()) { "#endif without #ifdef" }
                    active.removeAt(active.size - 1)
                }

                enclosing(active) -> {
                    out += line
                }
            }
        }
        require(active.isEmpty()) { "#ifdef without #endif" }
        return out.joinToString("\n")
    }

    private fun nameOf(directive: String): String = directive.substringAfter(' ').trim()

    private fun enclosing(active: List<Boolean>): Boolean = active.all { it }
}
