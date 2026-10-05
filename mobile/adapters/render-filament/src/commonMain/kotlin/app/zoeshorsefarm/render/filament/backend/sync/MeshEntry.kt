package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.MeshHandle
import app.zoeshorsefarm.render.filament.backend.device.RenderableHandle
import app.zoeshorsefarm.render.filament.backend.mapping.MaterialMapping
import app.zoeshorsefarm.render.filament.backend.mapping.PrimitiveRanges
import app.zoeshorsefarm.render.filament.backend.mapping.RenderOrder
import app.zoeshorsefarm.render.filament.material.Blend
import app.zoeshorsefarm.render.filament.mesh.Aabb
import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.render.filament.mesh.RenderableOptions
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.StandardMaterial

/**
 * Keeps the Filament renderable of a `Mesh` (also `InstancedMesh`, `SkinnedMesh` and meshes with a
 * material list) in step with the node. The steady path (nothing changed) allocates nothing and
 * makes no native call except the bone matrices of skinned meshes.
 *
 * The draw plan (which index ranges are drawn with which material instance) is made again when the
 * geometry's layout or draw range, the materials or their `version` changed. The renderable is
 * rebuilt when the geometry was uploaded again, the instance or bone count changed or the number of
 * primitives changed; anything else (ranges, materials, shadows, culling, priority, bounds, transform)
 * is changed in place.
 */
@Suppress("TooManyFunctions") // one step of the update each
internal class MeshEntry(
    private val mesh: Mesh,
) : DrawEntry(mesh),
    GeometryUser,
    MaterialUser {
    private val instances = (mesh as? InstancedMesh)?.let { InstanceFeed(it) }
    private val skin = (mesh as? SkinnedMesh)?.let { SkinFeed(it) }
    private val transform = TransformCache()
    private val key = PlanKey()
    private val skinBox = Aabb()
    private val skinCandidate = Aabb()

    private var plan: DrawPlan? = null
    private var geometry: GeometryEntry? = null
    private var renderable: RenderableHandle? = null
    private var built: Built? = null
    private var shown = false
    private var listening = false
    private var boundData: Any? = null
    private var bookedPlan: DrawPlan? = null
    private var bookedBits = -1
    private var failed: DrawPlan? = null
    private val applied = Applied()

    /** What the live renderable was built from; any difference means a rebuild. */
    private class Built(
        val mesh: MeshHandle,
        val instanceCount: Int,
        val boneCount: Int,
        val plan: DrawPlan,
    ) {
        /** True if a renderable built like this can draw `current` on `target` without being rebuilt. */
        fun fits(
            target: MeshHandle,
            instances: Int,
            bones: Int,
            current: DrawPlan,
        ): Boolean =
            mesh === target && instanceCount == instances && boneCount == bones &&
                plan.ranges.size == current.ranges.size
    }

    /** The per frame state last sent to the renderable. */
    private class Applied {
        var castShadow = false
        var receiveShadow = false
        var culling = false
        var renderOrder = 0
        var blendOrder = 0
        var fog = true
        var boundsOwner: Any? = null
        var boundsVersion = -1
        var skinVersion = 0
    }

    override val uploadOverflows: Int get() = instances?.uploadOverflows ?: 0

    override fun update(
        ctx: SyncContext,
        visible: Boolean,
    ) {
        if (visible && draw(ctx)) return
        hide()
    }

    /** Brings the renderable up to date and shows it; false if there is nothing to draw. */
    private fun draw(ctx: SyncContext): Boolean {
        watchInstanced(ctx)
        val g = acquireGeometry(ctx)
        val current = if (g != null) currentPlan(ctx, g) else null
        val instanceCount = instances?.count ?: 1
        val live = if (g != null && current != null) ensureRenderable(ctx, g, current, instanceCount) else null
        if (g == null || current == null || live == null) return false
        sync(ctx, live, g, current)
        show(live)
        ctx.countDraw(current.ranges, instanceCount, mesh.castShadow)
        return true
    }

    /** The draw plan, made again when its inputs changed; null if it draws nothing. */
    private fun currentPlan(
        ctx: SyncContext,
        g: GeometryEntry,
    ): DrawPlan? {
        if (plan == null || !key.matches(mesh, skin)) rebuildPlan(ctx, g)
        val current = plan
        val empty = current == null || current.isEmpty || instances?.count == 0
        return if (empty) null else current
    }

    override fun onDisposed(
        ctx: SyncContext,
        resource: GpuObject,
    ) {
        if (instances == null || resource !== instances.mesh) return
        // the instance data texture is gone: the renderable and the instance bound materials go with it
        destroyRenderable()
        releaseOwnedBindings(ctx)
        plan = null
        boundData = null
        instances.release()
    }

    override fun destroy(ctx: SyncContext) {
        destroyRenderable()
        releaseOwnedBindings(ctx)
        detachPlan()
        instances?.release()
        if (listening) instances?.mesh?.removeDisposeListener(ctx.disposeListener)
        listening = false
    }

    override fun onGeometryReset() {
        // the registry destroys the mesh and clears its user list itself
        geometry = null
        destroyRenderable()
    }

    override fun onMaterialReset() {
        // the registry clears the user lists of the bindings itself
        destroyRenderable()
        plan = null
        boundData = null
    }

    private fun detachPlan() {
        val old = plan ?: return
        for (i in old.bindings.indices) old.bindings[i].users.remove(this)
        plan = null
    }

    // ---- plan ----------------------------------------------------------------------------------

    private fun acquireGeometry(ctx: SyncContext): GeometryEntry? {
        var lit = false
        var uvTangents = false
        forEachMaterial { material ->
            if (material is StandardMaterial) {
                lit = true
                if (material.normalMap != null) uvTangents = true
            } else if (material is LambertMaterial) {
                lit = true
            }
        }
        return ctx.geometries.acquire(mesh.geometry, uvTangents, lit)
    }

    private inline fun forEachMaterial(block: (Material) -> Unit) {
        val list = mesh.materialArray
        if (list == null) {
            block(mesh.material)
        } else {
            for (i in list.indices) block(list[i])
        }
    }

    private fun materialAt(index: Int): Material? {
        val list = mesh.materialArray ?: return if (index == 0) mesh.material else null
        return list.getOrNull(index)
    }

    private fun rebuildPlan(
        ctx: SyncContext,
        g: GeometryEntry,
    ) {
        val ranges = PrimitiveRanges.of(g.geometry, mesh.materialArray != null)
        val kept = ArrayList<PrimitiveRange>(ranges.size)
        val bindings = ArrayList<MaterialBinding>(ranges.size)
        for (range in ranges) {
            val binding = bindingFor(ctx, g, range)
            if (binding != null) {
                kept += range
                bindings += binding
            }
        }
        detachPlan()
        val fresh = DrawPlan(kept, bindings)
        plan = fresh
        // a material that is disposed must reach the entry before the next frame, whether or not it is drawn yet
        for (i in bindings.indices) if (this !in bindings[i].users) bindings[i].users += this
        // the instance data goes to the new material instances
        boundData = null
        key.capture(mesh, skin)
    }

    private fun bindingFor(
        ctx: SyncContext,
        g: GeometryEntry,
        range: PrimitiveRange,
    ): MaterialBinding? {
        val material = materialAt(range.materialIndex) ?: return null
        val spec = MaterialMapping.specFor(mesh, material, g.geometry)
        if (spec == null) {
            ctx.log.warn("material '${material.name}' (${material.type}) has no Filament shader and is not drawn")
            return null
        }
        return ctx.materials.bind(material, spec, if (spec.usesInstanceData) mesh else null)
    }

    // ---- renderable ----------------------------------------------------------------------------

    private fun ensureRenderable(
        ctx: SyncContext,
        g: GeometryEntry,
        current: DrawPlan,
        instanceCount: Int,
    ): RenderableHandle? {
        val boneCount = skin?.boneCount ?: 0
        val feed = instances
        if (feed != null) feed.upload(ctx.device, g.bounds, mesh.frustumCulled)
        val live = renderable
        val old = built
        if (live != null && old != null && old.fits(g.mesh, instanceCount, boneCount, current)) {
            if (old.plan !== current) reusePlan(live, old, current)
            return live
        }
        destroyRenderable()
        if (failed === current) return null
        return try {
            build(ctx, g, current, instanceCount, boneCount)
        } catch (e: IllegalStateException) {
            failed = current
            ctx.log.warn("mesh '${mesh.name}' cannot be drawn: ${e.message}")
            null
        }
    }

    private fun build(
        ctx: SyncContext,
        g: GeometryEntry,
        current: DrawPlan,
        instanceCount: Int,
        boneCount: Int,
    ): RenderableHandle? {
        val primary = current.bindings[0].material
        val options =
            RenderableOptions(
                castShadows = mesh.castShadow,
                receiveShadows = mesh.receiveShadow,
                culling = mesh.frustumCulled,
                priority = RenderOrder.priority(mesh.renderOrder),
                blendOrder = blendOrderOf(current),
                fog = primary.fog,
                bounds = currentBounds(g),
                instanceCount = if (instances != null) instanceCount else 0,
                boneCount = boneCount,
            )
        val created =
            ctx.device.createRenderable(g.mesh, current.instances, current.ranges, options)
        renderable = created
        built = Built(g.mesh, instanceCount, boneCount, current)
        geometry = g
        g.users += this
        shown = true
        transform.invalidate()
        recordApplied(g, current)
        return created
    }

    private fun reusePlan(
        live: RenderableHandle,
        old: Built,
        current: DrawPlan,
    ) {
        var rangesChanged = false
        for (i in current.ranges.indices) {
            if (old.plan.ranges[i] != current.ranges[i]) rangesChanged = true
            if (old.plan.bindings[i] !== current.bindings[i]) live.setMaterial(i, current.bindings[i].instance)
        }
        if (rangesChanged) live.setRanges(current.ranges)
        built = Built(old.mesh, old.instanceCount, old.boneCount, current)
        if (instances != null) boundData = null
    }

    private fun destroyRenderable() {
        renderable?.destroy()
        renderable = null
        built = null
        shown = false
        geometry?.users?.remove(this)
        geometry = null
        transform.invalidate()
    }

    private fun releaseOwnedBindings(ctx: SyncContext) {
        val owned = plan?.bindings?.filter { it.owner === mesh } ?: return
        for (binding in owned) ctx.materials.release(binding)
    }

    private fun watchInstanced(ctx: SyncContext) {
        if (instances == null || listening) return
        instances.mesh.addDisposeListener(ctx.disposeListener)
        listening = true
    }

    // ---- per frame -----------------------------------------------------------------------------

    private fun sync(
        ctx: SyncContext,
        live: RenderableHandle,
        g: GeometryEntry,
        current: DrawPlan,
    ) {
        bindInstanceData(current)
        val bindings = current.bindings
        for (i in bindings.indices) {
            bindings[i].sync(ctx.frame)
            ctx.noteWind(bindings[i].wind)
        }
        bookPrograms(ctx, current)
        syncFlags(live, current)
        syncBounds(live, g)
        if (instances == null) {
            if (transform.refresh(mesh.matrixWorld)) live.setTransform(transform.current)
        }
        val palette = skin?.compute()
        if (palette != null) live.setBones(palette)
    }

    private fun bindInstanceData(current: DrawPlan) {
        val data = instances?.data ?: return
        if (data === boundData) return
        val bindings = current.bindings
        for (i in bindings.indices) {
            if (bindings[i].spec.usesInstanceData) bindings[i].instance.setInstanceData(data)
        }
        boundData = data
    }

    private fun bookPrograms(
        ctx: SyncContext,
        current: DrawPlan,
    ) {
        // only when the plan or the scene state changed
        val bits = ctx.programStateBits(mesh.receiveShadow)
        if (current === bookedPlan && bits == bookedBits) return
        for (binding in current.bindings) ctx.bookProgram(mesh, binding.material)
        bookedPlan = current
        bookedBits = bits
    }

    /** The blend order of the renderable: set while any of its materials blends. */
    private fun blendOrderOf(current: DrawPlan): Int {
        val bindings = current.bindings
        for (i in bindings.indices) {
            if (bindings[i].spec.blend == Blend.TRANSPARENT) return RenderOrder.blendOrder(mesh.renderOrder)
        }
        return 0
    }

    private fun syncFlags(
        live: RenderableHandle,
        current: DrawPlan,
    ) {
        val primary = current.bindings[0].material
        val state = applied
        if (mesh.castShadow != state.castShadow) {
            state.castShadow = mesh.castShadow
            live.setCastShadows(state.castShadow)
        }
        if (mesh.receiveShadow != state.receiveShadow) {
            state.receiveShadow = mesh.receiveShadow
            live.setReceiveShadows(state.receiveShadow)
        }
        if (mesh.frustumCulled != state.culling) {
            state.culling = mesh.frustumCulled
            live.setCulling(state.culling)
        }
        val blend = blendOrderOf(current)
        if (mesh.renderOrder != state.renderOrder || blend != state.blendOrder) {
            state.renderOrder = mesh.renderOrder
            state.blendOrder = blend
            live.setDrawOrder(RenderOrder.priority(state.renderOrder), blend)
        }
        if (primary.fog != state.fog) {
            state.fog = primary.fog
            live.setFog(state.fog)
        }
    }

    private fun syncBounds(
        live: RenderableHandle,
        g: GeometryEntry,
    ) {
        val state = applied
        val box = currentBounds(g)
        val version = boundsVersion(g)
        if (box === state.boundsOwner && version == state.boundsVersion) return
        state.boundsOwner = box
        state.boundsVersion = version
        live.setBounds(box)
    }

    /** The box Filament culls the renderable with. */
    private fun currentBounds(g: GeometryEntry): Aabb {
        val feed = instances
        if (feed != null && mesh.frustumCulled && feed.count > 0) return feed.bounds
        val sphere = mesh.boundingSphere
        if (skin != null && sphere != null) {
            skinCandidate.setFromSphere(
                sphere.center.x.toFloat(),
                sphere.center.y.toFloat(),
                sphere.center.z.toFloat(),
                sphere.radius.toFloat(),
            )
            if (!skinCandidate.min.contentEquals(skinBox.min) || !skinCandidate.max.contentEquals(skinBox.max)) {
                skinBox.copyFrom(skinCandidate)
                applied.skinVersion++
            }
            return skinBox
        }
        return g.bounds
    }

    private fun boundsVersion(g: GeometryEntry): Int =
        when {
            instances != null && mesh.frustumCulled && instances.count > 0 -> instances.boundsVersion
            skin != null && mesh.boundingSphere != null -> applied.skinVersion
            else -> g.boundsVersion
        }

    private fun recordApplied(
        g: GeometryEntry,
        current: DrawPlan,
    ) {
        val primary = current.bindings[0].material
        val state = applied
        state.castShadow = mesh.castShadow
        state.receiveShadow = mesh.receiveShadow
        state.culling = mesh.frustumCulled
        state.renderOrder = mesh.renderOrder
        state.blendOrder = blendOrderOf(current)
        state.fog = primary.fog
        state.boundsOwner = currentBounds(g)
        state.boundsVersion = boundsVersion(g)
    }

    private fun show(live: RenderableHandle) {
        if (shown) return
        live.setVisible(true)
        shown = true
    }

    private fun hide() {
        val live = renderable ?: return
        if (!shown) return
        live.setVisible(false)
        shown = false
    }

    /** What the draw plan was made from. */
    private class PlanKey {
        private var geometry: Any? = null
        private var list: List<Material>? = null
        private var single: Material? = null
        private val stamp = IntArray(STAMP_SIZE)
        private val probe = IntArray(STAMP_SIZE)
        private var versions = IntArray(0)

        fun matches(
            mesh: Mesh,
            skin: SkinFeed?,
        ): Boolean {
            if (!sameObjects(mesh)) return false
            fill(probe, mesh, skin)
            return probe.contentEquals(stamp) && versionsMatch(mesh)
        }

        fun capture(
            mesh: Mesh,
            skin: SkinFeed?,
        ) {
            geometry = mesh.geometry
            list = mesh.materialArray
            single = if (list == null) mesh.material else null
            fill(stamp, mesh, skin)
            val count = list?.size ?: 1
            if (versions.size != count) versions = IntArray(count)
            for (i in 0 until count) versions[i] = (list?.get(i) ?: mesh.material).version
        }

        private fun sameObjects(mesh: Mesh): Boolean {
            val materials = mesh.materialArray
            return mesh.geometry === geometry && materials === list && (materials != null || mesh.material === single)
        }

        /** The numbers of the geometry's layout and draw range, the colour of the instances and the bones. */
        private fun fill(
            out: IntArray,
            mesh: Mesh,
            skin: SkinFeed?,
        ) {
            val g = mesh.geometry
            out[0] = g.structureVersion
            out[1] = g.indexVersion
            out[2] = g.vertexCount
            out[3] = g.drawRange.start
            out[4] = g.drawRange.count
            out[5] = g.groups.size
            out[6] = if ((mesh as? InstancedMesh)?.instanceColor != null) 1 else 0
            out[7] = skin?.boneCount ?: -1
        }

        private fun versionsMatch(mesh: Mesh): Boolean {
            val materials = mesh.materialArray
            if (materials == null) return mesh.material.version == versions[0]
            for (i in materials.indices) if (materials[i].version != versions[i]) return false
            return true
        }

        private companion object {
            const val STAMP_SIZE = 8
        }
    }
}
