package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.GpuDevice
import app.zoeshorsefarm.render.filament.backend.device.TextureHandle
import app.zoeshorsefarm.render.filament.backend.mapping.TextureMapping
import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.texture.Texture

/** Something that has a texture bound and must bind the new one when the texture is uploaded again or freed. */
internal interface TextureUser {
    fun onTextureChanged(
        texture: Texture,
        handle: TextureHandle,
    )

    fun onTextureFreed(texture: Texture)
}

/**
 * The device copies of the scene's textures. A texture is uploaded when it is first asked for and
 * again when its `version` changed (the raster was drawn into: the new handle replaces the old one
 * and every user is told); `free` is what `Texture.dispose()` does.
 */
internal class TextureRegistry(
    private val device: GpuDevice,
    private val disposeListener: DisposeListener,
) {
    private class Entry(
        val texture: Texture,
        var handle: TextureHandle,
        var version: Int,
    ) {
        val users = ArrayList<TextureUser>(1)
    }

    private val entries = HashMap<Texture, Entry>()

    /** Textures on the GPU. */
    val count: Int get() = entries.size

    /** The current handle of `texture`; uploads it first if needed. */
    fun acquire(texture: Texture): TextureHandle {
        val entry = entries[texture]
        if (entry == null) return upload(texture).handle
        if (entry.version != texture.version) reupload(entry)
        return entry.handle
    }

    /** `user` is told when the handle of `texture` changes. */
    fun watch(
        texture: Texture,
        user: TextureUser,
    ) {
        val entry = entries[texture] ?: upload(texture)
        if (user !in entry.users) entry.users += user
    }

    /** Stops telling `user` about `texture`. */
    fun unwatch(
        texture: Texture,
        user: TextureUser,
    ) {
        entries[texture]?.users?.remove(user)
    }

    /** Frees the device copy of a disposed texture; the next `acquire` uploads it again. */
    fun free(texture: Texture) {
        val entry = entries.remove(texture) ?: return
        texture.removeDisposeListener(disposeListener)
        for (i in entry.users.indices) entry.users[i].onTextureFreed(texture)
        entry.handle.destroy()
    }

    fun clear() {
        for (entry in entries.values) {
            entry.texture.removeDisposeListener(disposeListener)
            entry.handle.destroy()
        }
        entries.clear()
    }

    private fun upload(texture: Texture): Entry {
        val handle = createHandle(texture)
        val entry = Entry(texture, handle, texture.version)
        entries[texture] = entry
        texture.addDisposeListener(disposeListener)
        return entry
    }

    private fun reupload(entry: Entry) {
        val old = entry.handle
        val handle = createHandle(entry.texture)
        entry.handle = handle
        entry.version = entry.texture.version
        for (i in entry.users.indices) entry.users[i].onTextureChanged(entry.texture, handle)
        old.destroy()
    }

    private fun createHandle(texture: Texture): TextureHandle {
        val label = texture.name.ifEmpty { "texture ${texture.width}x${texture.height}" }
        return device.createTexture(label, TextureMapping.toData(texture, device.maxAnisotropy))
    }
}
