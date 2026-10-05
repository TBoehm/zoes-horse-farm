package app.zoeshorsefarm.scene

import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.abs
import kotlin.test.assertTrue

/** Expected values in the tests of this module were computed with three.js r186 (node). */
const val EPS = 1e-9

fun assertNear(
    expected: Double,
    actual: Double,
    eps: Double = EPS,
    message: String = "",
) {
    assertTrue(abs(expected - actual) <= eps * maxOf(1.0, abs(expected)), "$message expected $expected but was $actual")
}

fun assertNear(
    expected: DoubleArray,
    actual: DoubleArray,
    eps: Double = EPS,
    message: String = "",
) {
    assertTrue(expected.size == actual.size, "$message size ${expected.size} != ${actual.size}")
    for (i in expected.indices) assertNear(expected[i], actual[i], eps, "$message [$i]")
}

fun assertVec(
    x: Double,
    y: Double,
    z: Double,
    v: Vec3,
    eps: Double = EPS,
) = assertNear(doubleArrayOf(x, y, z), doubleArrayOf(v.x, v.y, v.z), eps)

fun assertQuat(
    x: Double,
    y: Double,
    z: Double,
    w: Double,
    q: Quat,
    eps: Double = EPS,
) = assertNear(doubleArrayOf(x, y, z, w), doubleArrayOf(q.x, q.y, q.z, q.w), eps)

fun assertMat(
    expected: DoubleArray,
    m: Mat4,
    eps: Double = EPS,
) = assertNear(expected, m.e, eps)
