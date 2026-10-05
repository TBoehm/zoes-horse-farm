package app.zoeshorsefarm.scene.geometry

import app.zoeshorsefarm.scene.math.Mat3
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Vec3

/** How often the GPU copy of a buffer is rewritten (three.js `StaticDrawUsage` / `DynamicDrawUsage`). */
enum class Usage { STATIC_DRAW, DYNAMIC_DRAW }

/**
 * Typed vertex data (three.js `BufferAttribute`). Write to [FloatAttribute.array] or call the
 * setters, then set [needsUpdate] so a backend uploads the data again ([version] counts that).
 */
sealed class BufferAttribute(
    val itemSize: Int,
) {
    var usage: Usage = Usage.STATIC_DRAW

    /** Incremented every time [needsUpdate] is set to true; the backend re-uploads when it changes. */
    var version: Int = 0
        private set

    abstract val count: Int

    var needsUpdate: Boolean = false
        set(value) {
            field = value
            if (value) version++
        }

    fun setUsage(usage: Usage): BufferAttribute {
        this.usage = usage
        return this
    }

    abstract fun clone(): BufferAttribute

    protected fun copyMetaTo(other: BufferAttribute): BufferAttribute {
        other.usage = usage
        return other
    }
}

/** Float32 attribute (position, normal, uv, colour, skin weights, custom attributes). */
class FloatAttribute(
    var array: FloatArray,
    itemSize: Int,
) : BufferAttribute(itemSize) {
    override val count: Int get() = array.size / itemSize

    constructor(values: List<Double>, itemSize: Int) : this(
        FloatArray(values.size) { values[it].toFloat() },
        itemSize,
    )

    fun getX(index: Int): Double = array[index * itemSize].toDouble()

    fun getY(index: Int): Double = array[index * itemSize + 1].toDouble()

    fun getZ(index: Int): Double = array[index * itemSize + 2].toDouble()

    fun getW(index: Int): Double = array[index * itemSize + 3].toDouble()

    fun setX(
        index: Int,
        x: Double,
    ): FloatAttribute {
        array[index * itemSize] = x.toFloat()
        return this
    }

    fun setY(
        index: Int,
        y: Double,
    ): FloatAttribute {
        array[index * itemSize + 1] = y.toFloat()
        return this
    }

    fun setZ(
        index: Int,
        z: Double,
    ): FloatAttribute {
        array[index * itemSize + 2] = z.toFloat()
        return this
    }

    fun setW(
        index: Int,
        w: Double,
    ): FloatAttribute {
        array[index * itemSize + 3] = w.toFloat()
        return this
    }

    fun setXY(
        index: Int,
        x: Double,
        y: Double,
    ): FloatAttribute {
        val i = index * itemSize
        array[i] = x.toFloat()
        array[i + 1] = y.toFloat()
        return this
    }

    fun setXYZ(
        index: Int,
        x: Double,
        y: Double,
        z: Double,
    ): FloatAttribute {
        val i = index * itemSize
        array[i] = x.toFloat()
        array[i + 1] = y.toFloat()
        array[i + 2] = z.toFloat()
        return this
    }

    fun setXYZW(
        index: Int,
        x: Double,
        y: Double,
        z: Double,
        w: Double,
    ): FloatAttribute {
        val i = index * itemSize
        array[i] = x.toFloat()
        array[i + 1] = y.toFloat()
        array[i + 2] = z.toFloat()
        array[i + 3] = w.toFloat()
        return this
    }

    /** Reads item `index` (itemSize >= 3) into `target`. */
    fun getVec3(
        index: Int,
        target: Vec3,
    ): Vec3 = target.set(getX(index), getY(index), getZ(index))

    /** Transforms every item (itemSize 3) as a point. */
    fun applyMatrix4(m: Mat4): FloatAttribute {
        val scratch = Vec3()
        for (i in 0 until count) {
            scratch.set(getX(i), getY(i), getZ(i)).applyMatrix4(m)
            setXYZ(i, scratch.x, scratch.y, scratch.z)
        }
        return this
    }

    /** Transforms every item (itemSize 3) as a normal and renormalises it. */
    fun applyNormalMatrix(m: Mat3): FloatAttribute {
        val scratch = Vec3()
        for (i in 0 until count) {
            scratch.set(getX(i), getY(i), getZ(i)).applyNormalMatrix(m)
            setXYZ(i, scratch.x, scratch.y, scratch.z)
        }
        return this
    }

    override fun clone(): FloatAttribute = copyMetaTo(FloatAttribute(array.copyOf(), itemSize)) as FloatAttribute
}

/** Uint16 attribute (bone indices of skinned meshes); values are 0..65535. */
class UShortAttribute(
    var array: ShortArray,
    itemSize: Int,
) : BufferAttribute(itemSize) {
    override val count: Int get() = array.size / itemSize

    constructor(values: IntArray, itemSize: Int) : this(ShortArray(values.size) { values[it].toShort() }, itemSize)

    constructor(values: List<Int>, itemSize: Int) : this(ShortArray(values.size) { values[it].toShort() }, itemSize)

    fun getX(index: Int): Int = array[index * itemSize].toInt() and 0xFFFF

    fun getY(index: Int): Int = array[index * itemSize + 1].toInt() and 0xFFFF

    fun getZ(index: Int): Int = array[index * itemSize + 2].toInt() and 0xFFFF

    fun getW(index: Int): Int = array[index * itemSize + 3].toInt() and 0xFFFF

    fun setXYZW(
        index: Int,
        x: Int,
        y: Int,
        z: Int,
        w: Int,
    ): UShortAttribute {
        val i = index * itemSize
        array[i] = x.toShort()
        array[i + 1] = y.toShort()
        array[i + 2] = z.toShort()
        array[i + 3] = w.toShort()
        return this
    }

    override fun clone(): UShortAttribute = copyMetaTo(UShortAttribute(array.copyOf(), itemSize)) as UShortAttribute
}
