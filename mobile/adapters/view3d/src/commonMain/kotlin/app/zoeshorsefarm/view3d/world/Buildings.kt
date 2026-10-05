package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.ConeGeometry
import app.zoeshorsefarm.scene.geometry.CylinderGeometry
import app.zoeshorsefarm.scene.geometry.ExtrudeGeometry
import app.zoeshorsefarm.scene.geometry.IcosahedronGeometry
import app.zoeshorsefarm.scene.geometry.PlaneGeometry
import app.zoeshorsefarm.scene.geometry.Shape
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.view3d.GeometryBuilder
import app.zoeshorsefarm.view3d.PartTransform
import app.zoeshorsefarm.view3d.SITE
import app.zoeshorsefarm.view3d.boxOnGround
import app.zoeshorsefarm.view3d.shadeByHeight
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

// Buildings of the riding facility and the props around them: stable, judges' hut, benches, bales,
// water trough and wheelbarrow. All of it goes into one geometry (one draw call).

private const val FRAME_WHITE = 0xece6d8
private const val DARK_METAL = 0x333333

/** The stable: walls, gables, roof, cupola, stall doors on the arena side and a barn door at the path. */
internal fun addStable(b: GeometryBuilder) {
    val st = SITE.stable
    val cx = st.x
    val cz = st.z
    val depth = st.depth
    val length = st.length
    val wallH = 3.6
    val plinth = 0.5
    val top = plinth + wallH
    val rise = 2.9
    val front = cx + depth / 2 // side facing the arena (+x)
    b.add(boxOnGround(depth + 0.2, plinth, length + 0.2), 0x8f8b84, PartTransform(x = cx, z = cz))
    val wall = b.add(boxOnGround(depth, wallH, length), 0x8d5b37, PartTransform(x = cx, y = plinth, z = cz))
    shadeByHeight(wall, plinth, top, 0.8, 1.05)
    // board battens on the front
    var bz = cz - length / 2 + 0.3
    while (bz < cz + length / 2) {
        b.add(boxOnGround(0.05, wallH, 0.07), 0x7a4c2c, PartTransform(x = front + 0.02, y = plinth, z = bz))
        bz += 0.75
    }
    addStableRoof(b, cx, cz, depth, length, top, rise)
    addStallDoors(b, cz, length, front, plinth)
    addBarnDoor(b, cx, cz, length, plinth)
}

private fun addStableRoof(
    b: GeometryBuilder,
    cx: Double,
    cz: Double,
    depth: Double,
    length: Double,
    top: Double,
    rise: Double,
) {
    // gables
    val tri = Shape(listOf(Vec2(-depth / 2, 0.0), Vec2(depth / 2, 0.0), Vec2(0.0, rise)))
    for (s in listOf(-1.0, 1.0)) {
        val gable = ExtrudeGeometry(tri, depth = 0.2)
        gable.translate(0.0, 0.0, -0.1)
        b.add(gable, 0x86542f, PartTransform(x = cx, y = top, z = cz + s * (length / 2 - 0.1), ry = PI / 2))
    }
    // roof
    val half = depth / 2 + 0.7
    val slab = hypot(half, rise + 0.35)
    val ang = atan2(rise + 0.35, half)
    for (s in listOf(-1.0, 1.0)) {
        b.add(
            BoxGeometry(slab, 0.18, length + 1.2),
            0x6b3427,
            PartTransform(x = cx + s * half / 2, y = top + rise / 2 - 0.05, z = cz, rz = -s * ang),
        )
    }
    b.add(BoxGeometry(0.35, 0.22, length + 1.3), 0x4f2a20, PartTransform(x = cx, y = top + rise + 0.1, z = cz))
    // cupola
    b.add(boxOnGround(1.4, 1.1, 1.4), 0xf0ebe0, PartTransform(x = cx, y = top + rise - 0.1, z = cz))
    val cap = ConeGeometry(1.15, 0.9, 4)
    cap.rotateY(PI / 4)
    b.add(cap, 0x4f2a20, PartTransform(x = cx, y = top + rise + 1.45, z = cz))
    b.add(CylinderGeometry(0.02, 0.02, 0.9, 4), DARK_METAL, PartTransform(x = cx, y = top + rise + 2.3, z = cz))
    b.add(BoxGeometry(0.03, 0.12, 0.45), DARK_METAL, PartTransform(x = cx, y = top + rise + 2.55, z = cz))
}

/** Stall doors: lower half closed (green), upper half open (dark), white frames. */
private fun addStallDoors(
    b: GeometryBuilder,
    cz: Double,
    length: Double,
    front: Double,
    plinth: Double,
) {
    val doors = 8
    for (i in 0 until doors) {
        val z = cz - length / 2 + (i + 0.5) * (length / doors)
        b.add(boxOnGround(0.08, 1.25, 1.2), 0x2f5b3a, PartTransform(x = front + 0.04, y = plinth, z = z))
        b.add(boxOnGround(0.06, 1.0, 1.2), 0x17120e, PartTransform(x = front + 0.02, y = plinth + 1.25, z = z))
        b.add(boxOnGround(0.1, 0.08, 1.4), FRAME_WHITE, PartTransform(x = front + 0.05, y = plinth + 2.25, z = z))
        for (dz in listOf(-0.66, 0.66)) {
            b.add(boxOnGround(0.1, 2.33, 0.08), FRAME_WHITE, PartTransform(x = front + 0.05, y = plinth, z = z + dz))
        }
        val diag = hypot(1.15, 1.15)
        for (s in listOf(-1.0, 1.0)) {
            b.add(
                BoxGeometry(0.1, 0.06, diag),
                FRAME_WHITE,
                PartTransform(x = front + 0.09, y = plinth + 0.62, z = z, rx = s * atan2(1.1, 1.15)),
            )
        }
    }
}

/** Barn door on the gable end facing the path. */
private fun addBarnDoor(
    b: GeometryBuilder,
    cx: Double,
    cz: Double,
    length: Double,
    plinth: Double,
) {
    val gz = cz + length / 2 + 0.06
    b.add(boxOnGround(3.2, 3.1, 0.1), 0x7e2f22, PartTransform(x = cx, y = plinth, z = gz))
    for (s in listOf(-1.0, 1.0)) {
        b.add(
            BoxGeometry(0.1, 4.3, 0.06),
            FRAME_WHITE,
            PartTransform(x = cx, y = plinth + 1.55, z = gz + 0.06, rz = s * atan2(3.0, 3.1)),
        )
    }
    b.add(boxOnGround(3.4, 0.12, 0.14), FRAME_WHITE, PartTransform(x = cx, y = plinth + 3.1, z = gz))
}

/** The judges' hut on stilts: walls, a large window towards the arena, a shed roof, stairs, flower boxes. */
internal fun addHut(b: GeometryBuilder) {
    val cx = SITE.hut.x
    val cz = SITE.hut.z
    val s = 3.2
    val floor = 1.3
    for (dx in listOf(-1.0, 1.0)) {
        for (dz in listOf(-1.0, 1.0)) {
            b.add(
                boxOnGround(0.16, floor, 0.16),
                0x6e5440,
                PartTransform(
                    x = cx + dx * (s / 2 - 0.15),
                    z =
                        cz + dz * (s / 2 - 0.15),
                ),
            )
        }
    }
    b.add(boxOnGround(s + 0.3, 0.18, s + 0.3), 0x7a5c44, PartTransform(x = cx, y = floor, z = cz))
    val wallY = floor + 0.18
    val h = 2.2
    val white = 0xf1ece2
    // back and side walls
    b.add(boxOnGround(0.12, h, s), white, PartTransform(x = cx + s / 2, y = wallY, z = cz))
    b.add(boxOnGround(s, h, 0.12), white, PartTransform(x = cx, y = wallY, z = cz - s / 2))
    b.add(boxOnGround(s, h, 0.12), white, PartTransform(x = cx, y = wallY, z = cz + s / 2))
    // front facing the arena: parapet, large window, lintel
    b.add(boxOnGround(0.12, 0.95, s), white, PartTransform(x = cx - s / 2, y = wallY, z = cz))
    b.add(boxOnGround(0.06, 0.95, s - 0.3), 0x2f3d48, PartTransform(x = cx - s / 2, y = wallY + 0.95, z = cz))
    b.add(boxOnGround(0.14, 0.3, s), white, PartTransform(x = cx - s / 2, y = wallY + 1.9, z = cz))
    for (dz in listOf(-1.0, 0.0, 1.0)) {
        b.add(
            boxOnGround(0.14, 0.95, 0.1),
            white,
            PartTransform(
                x = cx - s / 2,
                y = wallY + 0.95,
                z =
                    cz + dz * (s / 2 - 0.05),
            ),
        )
    }
    // shed roof
    b.add(BoxGeometry(s + 0.9, 0.14, s + 0.9), 0x2d4a3a, PartTransform(x = cx, y = wallY + h + 0.2, z = cz, rz = -0.14))
    addHutStairs(b, cx, cz, s)
    addFlowerBoxes(b, cx, cz, s, wallY)
}

/** Stairs up to the hut on its path side and the handrail post. */
private fun addHutStairs(
    b: GeometryBuilder,
    cx: Double,
    cz: Double,
    s: Double,
) {
    for (i in 0 until 5) {
        b.add(
            boxOnGround(0.9, 0.06, 0.3),
            0x7a5c44,
            PartTransform(
                x = cx + 0.6,
                y = (i + 1) * 0.26,
                z =
                    cz + s / 2 + 0.25 + (4 - i) * 0.3,
            ),
        )
    }
    b.add(boxOnGround(0.06, 1.8, 0.06), 0x6e5440, PartTransform(x = cx + 1.05, z = cz + s / 2 + 1.6))
}

/** Flower boxes below the large window. */
private fun addFlowerBoxes(
    b: GeometryBuilder,
    cx: Double,
    cz: Double,
    s: Double,
    wallY: Double,
) {
    for (dz in listOf(-0.9, 0.9)) {
        b.add(boxOnGround(0.25, 0.2, 0.9), 0x7a5c44, PartTransform(x = cx - s / 2 - 0.2, y = wallY + 0.75, z = cz + dz))
        b.add(
            IcosahedronGeometry(0.22, 0),
            0xd6455d,
            PartTransform(x = cx - s / 2 - 0.2, y = wallY + 1.05, z = cz + dz, sz = 2.0),
        )
    }
}

/** Benches along the long side, round bales at the stable, a water trough by the path and a wheelbarrow. */
internal fun addProps(
    b: GeometryBuilder,
    rng: () -> Double,
) {
    // spectator benches along the long side
    for (z in listOf(5.0, 12.0, 19.0)) {
        val x = 23.6
        b.add(boxOnGround(0.42, 0.06, 2.1), 0x8b6a4c, PartTransform(x = x, y = 0.44, z = z))
        b.add(boxOnGround(0.06, 0.38, 2.1), 0x8b6a4c, PartTransform(x = x + 0.22, y = 0.62, z = z, rz = 0.15))
        for (dz in listOf(-0.85, 0.85)) b.add(boxOnGround(0.4, 0.44, 0.08), 0x555555, PartTransform(x = x, z = z + dz))
    }

    // round bales at the stable
    fun bale(
        x: Double,
        y: Double,
        z: Double,
        ry: Double,
    ) {
        val g = CylinderGeometry(0.72, 0.72, 1.2, 14)
        g.rotateZ(PI / 2)
        b.add(g, 0xc9ad66, PartTransform(x = x, y = y + 0.72, z = z, ry = ry), jitter = 0.08, rng = rng)
    }
    bale(-37.2, 0.0, 36.6, 0.2)
    bale(-35.6, 0.0, 36.9, -0.1)
    bale(-36.4, 1.3, 36.7, 0.05)
    bale(-34.2, 0.0, 35.2, 1.4)
    // water trough by the path
    b.add(boxOnGround(1.8, 0.6, 0.6), 0x8a9099, PartTransform(x = -33.0, z = 26.4))
    val water = PlaneGeometry(1.65, 0.45)
    water.rotateX(-PI / 2)
    b.add(water, 0x3d6f8a, PartTransform(x = -33.0, y = 0.55, z = 26.4))
    // wheelbarrow
    b.add(BoxGeometry(0.7, 0.35, 1.0), 0x2e6b9c, PartTransform(x = -40.2, y = 0.55, z = 3.5, rx = 0.15))
    b.add(CylinderGeometry(0.2, 0.2, 0.08, 10), 0x222222, PartTransform(x = -40.2, y = 0.2, z = 4.1, rz = PI / 2))
}
