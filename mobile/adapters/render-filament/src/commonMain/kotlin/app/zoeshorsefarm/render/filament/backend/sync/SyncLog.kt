package app.zoeshorsefarm.render.filament.backend.sync

/** Where the synchronisation reports what it could not draw (once per cause, never per frame). */
fun interface SyncLog {
    fun warn(message: String)

    companion object {
        val SILENT = SyncLog { }
    }
}
