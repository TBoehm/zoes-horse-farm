package app.zoeshorsefarm.render.filament.material

/**
 * A place to keep compiled material packages between runs. Compiling a material at run time (glslang
 * and the shader translators inside filamat) is the slowest thing the backend does, so the app can
 * plug in a cache that survives a restart (a file in the app's cache directory): the compiler asks it
 * first and stores what it had to compile.
 */
interface MaterialPackageCache {
    fun get(key: String): ByteArray?

    fun put(
        key: String,
        data: ByteArray,
    )

    companion object {
        /** Name, content hash and the Filament version: a changed shader or a new engine misses the cache. */
        fun keyOf(
            source: MaterialSource,
            engineVersion: String,
        ): String = "${source.name}-${source.fingerprint()}-$engineVersion"
    }
}

class InMemoryPackageCache : MaterialPackageCache {
    private val packages = HashMap<String, ByteArray>()

    val size: Int get() = packages.size

    override fun get(key: String): ByteArray? = packages[key]

    override fun put(
        key: String,
        data: ByteArray,
    ) {
        packages[key] = data
    }
}

/** A 64 bit FNV-1a hash of everything in the source, as 16 hex digits. */
fun MaterialSource.fingerprint(): String {
    val text =
        buildString {
            append(name).append('|')
            append(shading)
                .append('|')
                .append(blending)
                .append('|')
                .append(culling)
                .append('|')
            append(doubleSided)
                .append('|')
                .append(depthWrite)
                .append('|')
                .append(customSurfaceShading)
                .append('|')
            append(instanced)
                .append('|')
                .append(linearFog)
                .append('|')
                .append(highPrecision)
                .append('|')
            append(requires.joinToString(",")).append('|')
            append(variables.joinToString(",")).append('|')
            append(
                parameters.joinToString(",") {
                    when (it) {
                        is UniformParameter -> "u:${it.name}:${it.type}"
                        is SamplerParameter -> "s:${it.name}:${it.filterable}:${it.highPrecision}"
                    }
                },
            ).append('|')
            append(filteredVariants.sorted().joinToString(",")).append('|')
            append(fragment).append('|')
            append(vertex.orEmpty())
        }
    var hash = FNV_OFFSET
    for (byte in text.encodeToByteArray()) {
        hash = (hash xor (byte.toLong() and BYTE_MASK)) * FNV_PRIME
    }
    return hash.toULong().toString(HEX_RADIX).padStart(HEX_DIGITS, '0')
}

private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIME = 1099511628211L
private const val BYTE_MASK = 0xffL
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 16
