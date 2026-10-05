package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.PlaneGeometry
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.MaterialParams
import app.zoeshorsefarm.scene.material.SandEffect
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.view3d.FENCE
import app.zoeshorsefarm.view3d.FencePlan
import app.zoeshorsefarm.view3d.FenceRun
import app.zoeshorsefarm.view3d.FenceStyle
import app.zoeshorsefarm.view3d.GeometryBuilder
import app.zoeshorsefarm.view3d.PADDOCK
import app.zoeshorsefarm.view3d.Paddock
import app.zoeshorsefarm.view3d.PathPiece
import app.zoeshorsefarm.view3d.createSandTextures
import app.zoeshorsefarm.view3d.createWind
import app.zoeshorsefarm.view3d.jsRound
import app.zoeshorsefarm.view3d.planFence
import app.zoeshorsefarm.view3d.planPaddockFence
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

// Arena: sand footing with track, wooden fence (instanced), gate; the start and finish lines are in
// CourseLinesView.kt. The worn track and the large-scale variation of the sand are a shader patch
// of the sand material in the web app (`patchSandMaterial`): here the `SandEffect` of the sand materials.

private const val SAND_TILE = 4.0 // m per texture tile
private const val PATH_HEIGHT = 0.004
private const val PATH_COLOR = 0xd9cfc0

/** Ground: arena + path to the stable in one geometry (same material). */
private fun buildSandGeometry(path: List<PathPiece>): Geometry {
    val builder = GeometryBuilder()
    val w = ARENA.width + 2 * (FENCE.offset + 0.5)
    val l = ARENA.length + 2 * (FENCE.offset + 0.5)
    val plane = PlaneGeometry(w, l, 4, 6)
    plane.rotateX(-PI / 2)
    setWorldUv(plane, SAND_TILE)
    builder.add(plane, 0xffffff)
    for (seg in path) {
        val g = PlaneGeometry(seg.w, seg.l, 1, max(1, jsRound(seg.l / 6)))
        g.rotateX(-PI / 2)
        g.rotateY(seg.ry)
        g.translate(seg.x, PATH_HEIGHT, seg.z)
        setWorldUv(g, SAND_TILE)
        builder.add(g, PATH_COLOR)
    }
    return builder.build()
}

/** UV = world coordinates / tile size (textures repeat evenly). */
private fun setWorldUv(
    geometry: Geometry,
    tile: Double,
) {
    val pos = geometry.position
    val uv = geometry.uv
    for (i in 0 until pos.count) uv.setXY(i, pos.getX(i) / tile, -pos.getZ(i) / tile)
    uv.needsUpdate = true
}

private const val ARENA_FENCE_COLOR = 0xf4f1ea
private const val WOOD_FENCE_COLOR = 0x8a6a4a
private const val GATE_COLOR = 0xe9e4d8
private const val PADDOCK_FENCE_COLOR = 0x9a7650

private fun fenceColor(style: FenceStyle): Int =
    when (style) {
        FenceStyle.ARENA -> ARENA_FENCE_COLOR
        FenceStyle.WOOD -> WOOD_FENCE_COLOR
        FenceStyle.PADDOCK -> PADDOCK_FENCE_COLOR
    }

private fun fenceHeight(style: FenceStyle): Double =
    when (style) {
        FenceStyle.ARENA -> FENCE.height + 0.05
        FenceStyle.PADDOCK -> 1.3
        FenceStyle.WOOD -> 1.0
    }

/** A rail of a fence: height of its middle above the ground and its thickness. */
private class Rail(
    val y: Double,
    val h: Double,
)

private fun railsOf(style: FenceStyle): List<Rail> =
    when (style) {
        // kick board, two rails
        FenceStyle.ARENA -> listOf(Rail(0.06, 0.28), Rail(0.62, 0.13), Rail(1.05, 0.13))

        FenceStyle.PADDOCK -> listOf(Rail(0.4, 0.11), Rail(0.8, 0.11), Rail(1.2, 0.11))

        FenceStyle.WOOD -> listOf(Rail(0.45, 0.1), Rail(0.85, 0.1))
    }

/** A post or board of the fence as an instance: place, rotation about X and Y, scale and colour. */
private class FenceItem(
    val x: Double,
    val y: Double,
    val z: Double,
    val rx: Double = 0.0,
    val ry: Double = 0.0,
    val sx: Double,
    val sy: Double,
    val sz: Double,
    val color: Int,
)

private fun postOf(
    x: Double,
    z: Double,
    style: FenceStyle,
) = FenceItem(x, 0.0, z, sx = FENCE.post, sy = fenceHeight(style), sz = FENCE.post, color = fenceColor(style))

private fun boardsOf(plan: FencePlan): List<FenceItem> =
    plan.segments.flatMap { s ->
        railsOf(s.style).map { r ->
            FenceItem(
                s.x,
                r.y,
                s.z,
                ry = s.ang,
                sx = FENCE.board,
                sy = r.h,
                sz = s.len + FENCE.post,
                color = fenceColor(s.style),
            )
        }
    }

/** Gate: two strong posts, two leaves with boards and a brace. */
private fun gateItems(
    plan: FencePlan,
    posts: MutableList<FenceItem>,
    boards: MutableList<FenceItem>,
) {
    val g = plan.gate ?: return
    for (z in listOf(g.z0, g.z1)) posts.add(FenceItem(g.x, 0.0, z, sx = 0.18, sy = 1.45, sz = 0.18, color = GATE_COLOR))
    val leaf = (g.z1 - g.z0 - 0.3) / 2
    for ((zc, sign) in listOf((g.z0 + 0.12 + leaf / 2) to 1.0, (g.z1 - 0.12 - leaf / 2) to -1.0)) {
        for (y in listOf(0.15, 0.55, 0.95)) {
            boards.add(FenceItem(g.x, y, zc, sx = 0.05, sy = 0.12, sz = leaf, color = GATE_COLOR))
        }
        // diagonal brace in the leaf
        boards.add(
            FenceItem(
                g.x,
                0.52,
                zc,
                rx = sign * atan2(0.8, leaf),
                sx = 0.045,
                sy = 0.1,
                sz = hypot(0.8, leaf) - 0.1,
                color = GATE_COLOR,
            ),
        )
        for (zz in listOf(zc - sign * leaf / 2, zc + sign * leaf / 2)) {
            boards.add(FenceItem(g.x, 0.12, zz, sx = 0.06, sy = 1.0, sz = 0.06, color = GATE_COLOR))
        }
    }
}

/** The fence meshes: [baseCounts] are the instances without the paddock fence (it comes last). */
class Fence(
    val posts: InstancedMesh,
    val boards: InstancedMesh,
    val basePosts: Int,
    val baseBoards: Int,
)

private fun makeFenceMesh(
    unit: Geometry,
    material: Material,
    list: List<FenceItem>,
    name: String,
): InstancedMesh {
    val mesh = InstancedMesh(unit, material, list.size)
    mesh.name = name
    val m = Mat4()
    val q = Quat()
    val e = Euler()
    val s = Vec3()
    val p = Vec3()
    val c = Color()
    list.forEachIndexed { i, item ->
        q.setFromEuler(e.set(item.rx, item.ry, 0.0))
        mesh.setMatrixAt(i, m.compose(p.set(item.x, item.y, item.z), q, s.set(item.sx, item.sy, item.sz)))
        c.set(item.color).multiplyScalar(0.94 + ((i * 7919) % 13) / 100.0)
        mesh.setColorAt(i, c)
    }
    mesh.instanceMatrix.needsUpdate = true
    mesh.instanceColor?.needsUpdate = true
    mesh.computeBoundingSphere()
    mesh.castShadow = true
    mesh.receiveShadow = true
    return mesh
}

/**
 * Builds post and board InstancedMeshes for all fences. The fence of the paddock (`extra`) comes
 * last in both meshes, so that `count` can leave it out.
 */
private fun buildFence(
    plan: FencePlan,
    extra: FencePlan,
    material: Material,
): Fence {
    val unit = BoxGeometry(1.0, 1.0, 1.0).translate(0.0, 0.5, 0.0)
    val posts = ArrayList<FenceItem>()
    val boards = ArrayList<FenceItem>()
    plan.posts.forEach { posts.add(postOf(it.x, it.z, it.style)) }
    boards.addAll(boardsOf(plan))
    gateItems(plan, posts, boards)
    val basePosts = posts.size
    val baseBoards = boards.size
    extra.posts.forEach { posts.add(postOf(it.x, it.z, it.style)) }
    boards.addAll(boardsOf(extra))
    return Fence(
        makeFenceMesh(unit, material, posts, "fence-posts"),
        makeFenceMesh(unit, material, boards, "fence-boards"),
        basePosts,
        baseBoards,
    )
}

/**
 * Arena. [materialFactory] returns a material pair (the world handles quality switches); [wind]
 * moves the bunting. [setDetail]: share > 0 adds the paddock fence, the bunting (share < 1: every
 * second pennant), the pots at the gate and the props of the paddock; 0 leaves them out (low level).
 */
class Arena(
    materialFactory: MaterialFactory,
    path: List<PathPiece>,
    pathFence: List<FenceRun>,
    wind: Wind = createWind(),
    paddock: Paddock = PADDOCK,
) {
    val group = Group().also { it.name = "arena" }
    val ground: Mesh
    val fence: Fence
    val plan: FencePlan
    val textures: List<Texture>
    val meshes: List<ManagedMesh>
    private val decor = ArenaDecor(materialFactory, wind)
    private val fenceTotals: Pair<Int, Int>

    init {
        val sand = createSandTextures(512)
        val sandMats =
            materialFactory(
                "sand",
                MaterialParams(
                    color = 0xffffff,
                    map = sand.map,
                    normalMap = sand.normalMap,
                    normalScale = Vec2(0.9, 0.9),
                    roughness = 0.97,
                    metalness = 0.0,
                    vertexColors = true,
                ),
            )
        // worn track and large-scale variation of the sand (a fragment patch of the backend)
        val sandEffect = SandEffect(ARENA.width / 2, ARENA.length / 2)
        sandMats.standard.effect = sandEffect
        sandMats.lambert.effect = sandEffect
        ground = Mesh(buildSandGeometry(path), sandMats.standard)
        ground.name = "arena-ground"
        ground.receiveShadow = true
        group.add(ground)

        val fenceMats = materialFactory("fence", MaterialParams(color = 0xffffff, roughness = 0.72))
        plan = planFence(pathFence)
        fence = buildFence(plan, planPaddockFence(paddock), fenceMats.standard)
        group.add(fence.posts, fence.boards)
        fenceTotals = fence.posts.count to fence.boards.count

        group.add(decor.group)
        textures = listOfNotNull(sand.map, sand.normalMap)
        meshes =
            listOf(
                ManagedMesh(ground, sandMats, ShadowRole.RECEIVE),
                ManagedMesh(fence.posts, fenceMats, ShadowRole.ALL),
                ManagedMesh(fence.boards, fenceMats, ShadowRole.ALL),
            ) + decor.meshes
    }

    fun setDetail(share: Double) {
        val withPaddock = share > 0
        fence.posts.count = if (withPaddock) fenceTotals.first else fence.basePosts
        fence.boards.count = if (withPaddock) fenceTotals.second else fence.baseBoards
        decor.setDetail(share)
    }
}
