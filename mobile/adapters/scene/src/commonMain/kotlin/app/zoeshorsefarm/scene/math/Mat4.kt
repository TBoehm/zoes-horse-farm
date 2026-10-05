package app.zoeshorsefarm.scene.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 4x4 matrix, column-major like three.js `Matrix4` (WebGL clip space, depth -1..1). */
class Mat4 {
    /** Column-major elements: `e[12..14]` is the translation. */
    val e: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    /** Arguments are in row-major order, like in three.js. */
    fun set(
        n11: Double,
        n12: Double,
        n13: Double,
        n14: Double,
        n21: Double,
        n22: Double,
        n23: Double,
        n24: Double,
        n31: Double,
        n32: Double,
        n33: Double,
        n34: Double,
        n41: Double,
        n42: Double,
        n43: Double,
        n44: Double,
    ): Mat4 {
        val te = e
        te[0] = n11
        te[4] = n12
        te[8] = n13
        te[12] = n14
        te[1] = n21
        te[5] = n22
        te[9] = n23
        te[13] = n24
        te[2] = n31
        te[6] = n32
        te[10] = n33
        te[14] = n34
        te[3] = n41
        te[7] = n42
        te[11] = n43
        te[15] = n44
        return this
    }

    fun identity(): Mat4 = set(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

    fun clone(): Mat4 = Mat4().copy(this)

    fun copy(m: Mat4): Mat4 {
        m.e.copyInto(e)
        return this
    }

    fun copyPosition(m: Mat4): Mat4 {
        e[12] = m.e[12]
        e[13] = m.e[13]
        e[14] = m.e[14]
        return this
    }

    fun extractBasis(
        xAxis: Vec3,
        yAxis: Vec3,
        zAxis: Vec3,
    ): Mat4 {
        if (determinantAffine() == 0.0) {
            xAxis.set(1.0, 0.0, 0.0)
            yAxis.set(0.0, 1.0, 0.0)
            zAxis.set(0.0, 0.0, 1.0)
            return this
        }
        xAxis.setFromMatrixColumn(this, 0)
        yAxis.setFromMatrixColumn(this, 1)
        zAxis.setFromMatrixColumn(this, 2)
        return this
    }

    fun makeBasis(
        xAxis: Vec3,
        yAxis: Vec3,
        zAxis: Vec3,
    ): Mat4 =
        set(
            xAxis.x,
            yAxis.x,
            zAxis.x,
            0.0,
            xAxis.y,
            yAxis.y,
            zAxis.y,
            0.0,
            xAxis.z,
            yAxis.z,
            zAxis.z,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
        )

    /** Copies only the rotation of `m` (the scale is divided out). */
    fun extractRotation(m: Mat4): Mat4 {
        if (m.determinantAffine() == 0.0) return identity()
        val me = m.e
        val scaleX = 1 / v1.setFromMatrixColumn(m, 0).length()
        val scaleY = 1 / v1.setFromMatrixColumn(m, 1).length()
        val scaleZ = 1 / v1.setFromMatrixColumn(m, 2).length()
        e[0] = me[0] * scaleX
        e[1] = me[1] * scaleX
        e[2] = me[2] * scaleX
        e[3] = 0.0
        e[4] = me[4] * scaleY
        e[5] = me[5] * scaleY
        e[6] = me[6] * scaleY
        e[7] = 0.0
        e[8] = me[8] * scaleZ
        e[9] = me[9] * scaleZ
        e[10] = me[10] * scaleZ
        e[11] = 0.0
        e[12] = 0.0
        e[13] = 0.0
        e[14] = 0.0
        e[15] = 1.0
        return this
    }

    fun makeRotationFromEuler(euler: Euler): Mat4 {
        val te = e
        val x = euler.x
        val y = euler.y
        val z = euler.z
        val a = cos(x)
        val b = sin(x)
        val c = cos(y)
        val d = sin(y)
        val ee = cos(z)
        val f = sin(z)
        when (euler.order) {
            EulerOrder.XYZ -> {
                val ae = a * ee
                val af = a * f
                val be = b * ee
                val bf = b * f
                te[0] = c * ee
                te[4] = -c * f
                te[8] = d
                te[1] = af + be * d
                te[5] = ae - bf * d
                te[9] = -b * c
                te[2] = bf - ae * d
                te[6] = be + af * d
                te[10] = a * c
            }

            EulerOrder.YXZ -> {
                val ce = c * ee
                val cf = c * f
                val de = d * ee
                val df = d * f
                te[0] = ce + df * b
                te[4] = de * b - cf
                te[8] = a * d
                te[1] = a * f
                te[5] = a * ee
                te[9] = -b
                te[2] = cf * b - de
                te[6] = df + ce * b
                te[10] = a * c
            }

            EulerOrder.ZXY -> {
                val ce = c * ee
                val cf = c * f
                val de = d * ee
                val df = d * f
                te[0] = ce - df * b
                te[4] = -a * f
                te[8] = de + cf * b
                te[1] = cf + de * b
                te[5] = a * ee
                te[9] = df - ce * b
                te[2] = -a * d
                te[6] = b
                te[10] = a * c
            }

            EulerOrder.ZYX -> {
                val ae = a * ee
                val af = a * f
                val be = b * ee
                val bf = b * f
                te[0] = c * ee
                te[4] = be * d - af
                te[8] = ae * d + bf
                te[1] = c * f
                te[5] = bf * d + ae
                te[9] = af * d - be
                te[2] = -d
                te[6] = b * c
                te[10] = a * c
            }

            EulerOrder.YZX -> {
                val ac = a * c
                val ad = a * d
                val bc = b * c
                val bd = b * d
                te[0] = c * ee
                te[4] = bd - ac * f
                te[8] = bc * f + ad
                te[1] = f
                te[5] = a * ee
                te[9] = -b * ee
                te[2] = -d * ee
                te[6] = ad * f + bc
                te[10] = ac - bd * f
            }

            EulerOrder.XZY -> {
                val ac = a * c
                val ad = a * d
                val bc = b * c
                val bd = b * d
                te[0] = c * ee
                te[4] = -f
                te[8] = d * ee
                te[1] = ac * f + bd
                te[5] = a * ee
                te[9] = ad * f - bc
                te[2] = bc * f - ad
                te[6] = b * ee
                te[10] = bd * f + ac
            }
        }
        te[3] = 0.0
        te[7] = 0.0
        te[11] = 0.0
        te[12] = 0.0
        te[13] = 0.0
        te[14] = 0.0
        te[15] = 1.0
        return this
    }

    fun makeRotationFromQuaternion(q: Quat): Mat4 = compose(zero, q, one)

    /** Rotation part looking from `eye` towards `target` (view-matrix orientation; translation untouched). */
    fun lookAt(
        eye: Vec3,
        target: Vec3,
        up: Vec3,
    ): Mat4 {
        val te = e
        axisZ.subVectors(eye, target)
        if (axisZ.lengthSq() == 0.0) axisZ.z = 1.0
        axisZ.normalize()
        axisX.crossVectors(up, axisZ)
        if (axisX.lengthSq() == 0.0) {
            // up and z are parallel
            if (abs(up.z) == 1.0) axisZ.x += 0.0001 else axisZ.z += 0.0001
            axisZ.normalize()
            axisX.crossVectors(up, axisZ)
        }
        axisX.normalize()
        axisY.crossVectors(axisZ, axisX)
        te[0] = axisX.x
        te[4] = axisY.x
        te[8] = axisZ.x
        te[1] = axisX.y
        te[5] = axisY.y
        te[9] = axisZ.y
        te[2] = axisX.z
        te[6] = axisY.z
        te[10] = axisZ.z
        return this
    }

    fun multiply(m: Mat4): Mat4 = multiplyMatrices(this, m)

    fun premultiply(m: Mat4): Mat4 = multiplyMatrices(m, this)

    fun multiplyMatrices(
        a: Mat4,
        b: Mat4,
    ): Mat4 {
        val ae = a.e
        val be = b.e
        val te = e
        val a11 = ae[0]
        val a12 = ae[4]
        val a13 = ae[8]
        val a14 = ae[12]
        val a21 = ae[1]
        val a22 = ae[5]
        val a23 = ae[9]
        val a24 = ae[13]
        val a31 = ae[2]
        val a32 = ae[6]
        val a33 = ae[10]
        val a34 = ae[14]
        val a41 = ae[3]
        val a42 = ae[7]
        val a43 = ae[11]
        val a44 = ae[15]
        val b11 = be[0]
        val b12 = be[4]
        val b13 = be[8]
        val b14 = be[12]
        val b21 = be[1]
        val b22 = be[5]
        val b23 = be[9]
        val b24 = be[13]
        val b31 = be[2]
        val b32 = be[6]
        val b33 = be[10]
        val b34 = be[14]
        val b41 = be[3]
        val b42 = be[7]
        val b43 = be[11]
        val b44 = be[15]
        te[0] = a11 * b11 + a12 * b21 + a13 * b31 + a14 * b41
        te[4] = a11 * b12 + a12 * b22 + a13 * b32 + a14 * b42
        te[8] = a11 * b13 + a12 * b23 + a13 * b33 + a14 * b43
        te[12] = a11 * b14 + a12 * b24 + a13 * b34 + a14 * b44
        te[1] = a21 * b11 + a22 * b21 + a23 * b31 + a24 * b41
        te[5] = a21 * b12 + a22 * b22 + a23 * b32 + a24 * b42
        te[9] = a21 * b13 + a22 * b23 + a23 * b33 + a24 * b43
        te[13] = a21 * b14 + a22 * b24 + a23 * b34 + a24 * b44
        te[2] = a31 * b11 + a32 * b21 + a33 * b31 + a34 * b41
        te[6] = a31 * b12 + a32 * b22 + a33 * b32 + a34 * b42
        te[10] = a31 * b13 + a32 * b23 + a33 * b33 + a34 * b43
        te[14] = a31 * b14 + a32 * b24 + a33 * b34 + a34 * b44
        te[3] = a41 * b11 + a42 * b21 + a43 * b31 + a44 * b41
        te[7] = a41 * b12 + a42 * b22 + a43 * b32 + a44 * b42
        te[11] = a41 * b13 + a42 * b23 + a43 * b33 + a44 * b43
        te[15] = a41 * b14 + a42 * b24 + a43 * b34 + a44 * b44
        return this
    }

    fun multiplyScalar(s: Double): Mat4 {
        for (i in 0 until 16) e[i] *= s
        return this
    }

    fun determinant(): Double {
        val te = e
        val n11 = te[0]
        val n12 = te[4]
        val n13 = te[8]
        val n14 = te[12]
        val n21 = te[1]
        val n22 = te[5]
        val n23 = te[9]
        val n24 = te[13]
        val n31 = te[2]
        val n32 = te[6]
        val n33 = te[10]
        val n34 = te[14]
        val n41 = te[3]
        val n42 = te[7]
        val n43 = te[11]
        val n44 = te[15]
        val t11 = n23 * n34 - n24 * n33
        val t12 = n22 * n34 - n24 * n32
        val t13 = n22 * n33 - n23 * n32
        val t21 = n21 * n34 - n24 * n31
        val t22 = n21 * n33 - n23 * n31
        val t23 = n21 * n32 - n22 * n31
        return n11 * (n42 * t11 - n43 * t12 + n44 * t13) -
            n12 * (n41 * t11 - n43 * t21 + n44 * t22) +
            n13 * (n41 * t12 - n42 * t21 + n44 * t23) -
            n14 * (n41 * t13 - n42 * t22 + n43 * t23)
    }

    /** Determinant of the upper 3x3 (used by decompose and extractRotation). */
    fun determinantAffine(): Double {
        val te = e
        val n11 = te[0]
        val n12 = te[4]
        val n13 = te[8]
        val n21 = te[1]
        val n22 = te[5]
        val n23 = te[9]
        val n31 = te[2]
        val n32 = te[6]
        val n33 = te[10]
        return n11 * (n22 * n33 - n23 * n32) -
            n12 * (n21 * n33 - n23 * n31) +
            n13 * (n21 * n32 - n22 * n31)
    }

    fun transpose(): Mat4 {
        val te = e
        var tmp = te[1]
        te[1] = te[4]
        te[4] = tmp
        tmp = te[2]
        te[2] = te[8]
        te[8] = tmp
        tmp = te[6]
        te[6] = te[9]
        te[9] = tmp
        tmp = te[3]
        te[3] = te[12]
        te[12] = tmp
        tmp = te[7]
        te[7] = te[13]
        te[13] = tmp
        tmp = te[11]
        te[11] = te[14]
        te[14] = tmp
        return this
    }

    fun setPosition(
        x: Double,
        y: Double,
        z: Double,
    ): Mat4 {
        e[12] = x
        e[13] = y
        e[14] = z
        return this
    }

    fun setPosition(v: Vec3): Mat4 = setPosition(v.x, v.y, v.z)

    /** Inverts in place; a singular matrix becomes all zeros (like three.js). */
    fun invert(): Mat4 {
        val te = e
        val n11 = te[0]
        val n21 = te[1]
        val n31 = te[2]
        val n41 = te[3]
        val n12 = te[4]
        val n22 = te[5]
        val n32 = te[6]
        val n42 = te[7]
        val n13 = te[8]
        val n23 = te[9]
        val n33 = te[10]
        val n43 = te[11]
        val n14 = te[12]
        val n24 = te[13]
        val n34 = te[14]
        val n44 = te[15]
        val t1 = n11 * n22 - n21 * n12
        val t2 = n11 * n32 - n31 * n12
        val t3 = n11 * n42 - n41 * n12
        val t4 = n21 * n32 - n31 * n22
        val t5 = n21 * n42 - n41 * n22
        val t6 = n31 * n42 - n41 * n32
        val t7 = n13 * n24 - n23 * n14
        val t8 = n13 * n34 - n33 * n14
        val t9 = n13 * n44 - n43 * n14
        val t10 = n23 * n34 - n33 * n24
        val t11 = n23 * n44 - n43 * n24
        val t12 = n33 * n44 - n43 * n34
        val det = t1 * t12 - t2 * t11 + t3 * t10 + t4 * t9 - t5 * t8 + t6 * t7
        if (det == 0.0) return set(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val detInv = 1 / det
        te[0] = (n22 * t12 - n32 * t11 + n42 * t10) * detInv
        te[1] = (n31 * t11 - n21 * t12 - n41 * t10) * detInv
        te[2] = (n24 * t6 - n34 * t5 + n44 * t4) * detInv
        te[3] = (n33 * t5 - n23 * t6 - n43 * t4) * detInv
        te[4] = (n32 * t9 - n12 * t12 - n42 * t8) * detInv
        te[5] = (n11 * t12 - n31 * t9 + n41 * t8) * detInv
        te[6] = (n34 * t3 - n14 * t6 - n44 * t2) * detInv
        te[7] = (n13 * t6 - n33 * t3 + n43 * t2) * detInv
        te[8] = (n12 * t11 - n22 * t9 + n42 * t7) * detInv
        te[9] = (n21 * t9 - n11 * t11 - n41 * t7) * detInv
        te[10] = (n14 * t5 - n24 * t3 + n44 * t1) * detInv
        te[11] = (n23 * t3 - n13 * t5 - n43 * t1) * detInv
        te[12] = (n22 * t8 - n12 * t10 - n32 * t7) * detInv
        te[13] = (n11 * t10 - n21 * t8 + n31 * t7) * detInv
        te[14] = (n24 * t2 - n14 * t4 - n34 * t1) * detInv
        te[15] = (n13 * t4 - n23 * t2 + n33 * t1) * detInv
        return this
    }

    /** Post-multiplies a scale: columns are scaled. */
    fun scale(v: Vec3): Mat4 {
        val te = e
        te[0] *= v.x
        te[4] *= v.y
        te[8] *= v.z
        te[1] *= v.x
        te[5] *= v.y
        te[9] *= v.z
        te[2] *= v.x
        te[6] *= v.y
        te[10] *= v.z
        te[3] *= v.x
        te[7] *= v.y
        te[11] *= v.z
        return this
    }

    fun getMaxScaleOnAxis(): Double {
        val te = e
        val scaleXSq = te[0] * te[0] + te[1] * te[1] + te[2] * te[2]
        val scaleYSq = te[4] * te[4] + te[5] * te[5] + te[6] * te[6]
        val scaleZSq = te[8] * te[8] + te[9] * te[9] + te[10] * te[10]
        return sqrt(max(scaleXSq, max(scaleYSq, scaleZSq)))
    }

    fun makeTranslation(
        x: Double,
        y: Double,
        z: Double,
    ): Mat4 = set(1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z, 0.0, 0.0, 0.0, 1.0)

    fun makeTranslation(v: Vec3): Mat4 = makeTranslation(v.x, v.y, v.z)

    fun makeRotationX(theta: Double): Mat4 {
        val c = cos(theta)
        val s = sin(theta)
        return set(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0)
    }

    fun makeRotationY(theta: Double): Mat4 {
        val c = cos(theta)
        val s = sin(theta)
        return set(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0, 0.0, 0.0, 0.0, 1.0)
    }

    fun makeRotationZ(theta: Double): Mat4 {
        val c = cos(theta)
        val s = sin(theta)
        return set(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    }

    /** `axis` must be normalised. */
    fun makeRotationAxis(
        axis: Vec3,
        angle: Double,
    ): Mat4 {
        val c = cos(angle)
        val s = sin(angle)
        val t = 1 - c
        val x = axis.x
        val y = axis.y
        val z = axis.z
        val tx = t * x
        val ty = t * y
        return set(
            tx * x + c,
            tx * y - s * z,
            tx * z + s * y,
            0.0,
            tx * y + s * z,
            ty * y + c,
            ty * z - s * x,
            0.0,
            tx * z - s * y,
            ty * z + s * x,
            t * z * z + c,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
        )
    }

    fun makeScale(
        x: Double,
        y: Double,
        z: Double,
    ): Mat4 = set(x, 0.0, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 0.0, z, 0.0, 0.0, 0.0, 0.0, 1.0)

    /** Builds translation * rotation * scale. */
    fun compose(
        position: Vec3,
        quaternion: Quat,
        scale: Vec3,
    ): Mat4 {
        val te = e
        val x = quaternion.x
        val y = quaternion.y
        val z = quaternion.z
        val w = quaternion.w
        val x2 = x + x
        val y2 = y + y
        val z2 = z + z
        val xx = x * x2
        val xy = x * y2
        val xz = x * z2
        val yy = y * y2
        val yz = y * z2
        val zz = z * z2
        val wx = w * x2
        val wy = w * y2
        val wz = w * z2
        val sx = scale.x
        val sy = scale.y
        val sz = scale.z
        te[0] = (1 - (yy + zz)) * sx
        te[1] = (xy + wz) * sx
        te[2] = (xz - wy) * sx
        te[3] = 0.0
        te[4] = (xy - wz) * sy
        te[5] = (1 - (xx + zz)) * sy
        te[6] = (yz + wx) * sy
        te[7] = 0.0
        te[8] = (xz + wy) * sz
        te[9] = (yz - wx) * sz
        te[10] = (1 - (xx + yy)) * sz
        te[11] = 0.0
        te[12] = position.x
        te[13] = position.y
        te[14] = position.z
        te[15] = 1.0
        return this
    }

    /** Splits into position, rotation and scale (a mirrored matrix gets a negative x scale). */
    fun decompose(
        position: Vec3,
        quaternion: Quat,
        scale: Vec3,
    ): Mat4 {
        val te = e
        position.x = te[12]
        position.y = te[13]
        position.z = te[14]
        val det = determinantAffine()
        if (det == 0.0) {
            scale.set(1.0, 1.0, 1.0)
            quaternion.identity()
            return this
        }
        var sx = v1.set(te[0], te[1], te[2]).length()
        val sy = v1.set(te[4], te[5], te[6]).length()
        val sz = v1.set(te[8], te[9], te[10]).length()
        if (det < 0) sx = -sx
        m1.copy(this)
        val invSX = 1 / sx
        val invSY = 1 / sy
        val invSZ = 1 / sz
        m1.e[0] *= invSX
        m1.e[1] *= invSX
        m1.e[2] *= invSX
        m1.e[4] *= invSY
        m1.e[5] *= invSY
        m1.e[6] *= invSY
        m1.e[8] *= invSZ
        m1.e[9] *= invSZ
        m1.e[10] *= invSZ
        quaternion.setFromRotationMatrix(m1)
        scale.x = sx
        scale.y = sy
        scale.z = sz
        return this
    }

    fun makePerspective(
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
        near: Double,
        far: Double,
    ): Mat4 {
        val te = e
        val x = 2 * near / (right - left)
        val y = 2 * near / (top - bottom)
        val a = (right + left) / (right - left)
        val b = (top + bottom) / (top - bottom)
        val c = -(far + near) / (far - near)
        val d = (-2 * far * near) / (far - near)
        te[0] = x
        te[4] = 0.0
        te[8] = a
        te[12] = 0.0
        te[1] = 0.0
        te[5] = y
        te[9] = b
        te[13] = 0.0
        te[2] = 0.0
        te[6] = 0.0
        te[10] = c
        te[14] = d
        te[3] = 0.0
        te[7] = 0.0
        te[11] = -1.0
        te[15] = 0.0
        return this
    }

    fun makeOrthographic(
        left: Double,
        right: Double,
        top: Double,
        bottom: Double,
        near: Double,
        far: Double,
    ): Mat4 {
        val te = e
        val x = 2 / (right - left)
        val y = 2 / (top - bottom)
        val a = -(right + left) / (right - left)
        val b = -(top + bottom) / (top - bottom)
        val c = -2 / (far - near)
        val d = -(far + near) / (far - near)
        te[0] = x
        te[4] = 0.0
        te[8] = 0.0
        te[12] = a
        te[1] = 0.0
        te[5] = y
        te[9] = 0.0
        te[13] = b
        te[2] = 0.0
        te[6] = 0.0
        te[10] = c
        te[14] = d
        te[3] = 0.0
        te[7] = 0.0
        te[11] = 0.0
        te[15] = 1.0
        return this
    }

    fun equals(m: Mat4): Boolean {
        for (i in 0 until 16) if (e[i] != m.e[i]) return false
        return true
    }

    fun fromArray(
        array: DoubleArray,
        offset: Int = 0,
    ): Mat4 {
        for (i in 0 until 16) e[i] = array[i + offset]
        return this
    }

    fun fromArray(
        array: FloatArray,
        offset: Int = 0,
    ): Mat4 {
        for (i in 0 until 16) e[i] = array[i + offset].toDouble()
        return this
    }

    fun toArray(
        out: DoubleArray = DoubleArray(16),
        offset: Int = 0,
    ): DoubleArray {
        e.copyInto(out, offset)
        return out
    }

    /** Writes the 16 elements as floats (GPU upload, instance buffers). */
    fun toFloatArray(
        out: FloatArray,
        offset: Int = 0,
    ): FloatArray {
        for (i in 0 until 16) out[offset + i] = e[i].toFloat()
        return out
    }

    override fun toString(): String = "Mat4(${e.joinToString()})"

    private companion object {
        // Scratch objects shared by the methods below (not thread-safe, like three.js).
        val v1 = Vec3()
        val m1 = Mat4()
        val zero = Vec3(0.0, 0.0, 0.0)
        val one = Vec3(1.0, 1.0, 1.0)
        val axisX = Vec3()
        val axisY = Vec3()
        val axisZ = Vec3()
    }
}
