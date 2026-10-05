package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.Node

/** The Filament side of one drawable scene node (a mesh, a points object or a sprite). */
internal abstract class DrawEntry(
    val node: Node,
) {
    /** The frame in which the walk last reached the node. */
    var seenFrame = -1

    /**
     * Brings the device objects in step with the node: creates them when the node is first drawn, sets
     * what changed, and keeps the entity out of the Filament scene while `visible` is false.
     */
    abstract fun update(
        ctx: SyncContext,
        visible: Boolean,
    )

    /** A scene object was disposed; drop what this entry holds of it (the registries free their own copies). */
    open fun onDisposed(
        ctx: SyncContext,
        resource: GpuObject,
    ) = Unit

    /** The node left the scene graph, or the backend shuts down: frees everything of the entry. */
    abstract fun destroy(ctx: SyncContext)
}
