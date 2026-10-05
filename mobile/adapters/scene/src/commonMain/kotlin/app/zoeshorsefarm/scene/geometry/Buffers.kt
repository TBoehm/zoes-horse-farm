package app.zoeshorsefarm.scene.geometry

/** Growable float list for geometry builders (values are narrowed to float on insertion, like Float32Array). */
class FloatBuf(
    initialCapacity: Int = 64,
) {
    private var data = FloatArray(initialCapacity)

    var size: Int = 0
        private set

    fun add(value: Double) {
        ensure(1)
        data[size++] = value.toFloat()
    }

    fun add(
        a: Double,
        b: Double,
    ) {
        ensure(2)
        data[size++] = a.toFloat()
        data[size++] = b.toFloat()
    }

    fun add(
        a: Double,
        b: Double,
        c: Double,
    ) {
        ensure(3)
        data[size++] = a.toFloat()
        data[size++] = b.toFloat()
        data[size++] = c.toFloat()
    }

    operator fun get(index: Int): Float = data[index]

    fun toFloatArray(): FloatArray = data.copyOf(size)

    fun toAttribute(itemSize: Int): FloatAttribute = FloatAttribute(toFloatArray(), itemSize)

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }
}

/** Growable int list for indices. */
class IntBuf(
    initialCapacity: Int = 64,
) {
    private var data = IntArray(initialCapacity)

    var size: Int = 0
        private set

    fun add(
        a: Int,
        b: Int,
        c: Int,
    ) {
        ensure(3)
        data[size++] = a
        data[size++] = b
        data[size++] = c
    }

    fun add(value: Int) {
        ensure(1)
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    fun toIntArray(): IntArray = data.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }
}

/** Growable double list for builders that read their own output back (polyhedra, extrusions). */
class DoubleBuf(
    initialCapacity: Int = 64,
) {
    private var data = DoubleArray(initialCapacity)

    var size: Int = 0
        private set

    fun add(value: Double) {
        if (size + 1 > data.size) data = data.copyOf(maxOf(data.size * 2, size + 1))
        data[size++] = value
    }

    fun add(
        a: Double,
        b: Double,
    ) {
        add(a)
        add(b)
    }

    fun add(
        a: Double,
        b: Double,
        c: Double,
    ) {
        add(a)
        add(b)
        add(c)
    }

    operator fun get(index: Int): Double = data[index]

    operator fun set(
        index: Int,
        value: Double,
    ) {
        data[index] = value
    }

    fun toAttribute(itemSize: Int): FloatAttribute = FloatAttribute(FloatArray(size) { data[it].toFloat() }, itemSize)
}
