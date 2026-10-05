package app.zoeshorsefarm.shared

/** Minimal event emitter: handlers per event type, delivered on a snapshot while emitting. */
class Emitter<P> {
    private val handlers = HashMap<String, LinkedHashSet<(P) -> Unit>>()

    /** Registers [handler] for [type]; returns the function that removes it again. */
    fun on(
        type: String,
        handler: (P) -> Unit,
    ): () -> Unit {
        handlers.getOrPut(type) { LinkedHashSet() }.add(handler)
        return { handlers[type]?.remove(handler) }
    }

    fun emit(
        type: String,
        payload: P,
    ) {
        val set = handlers[type] ?: return
        for (handler in set.toList()) handler(payload)
    }
}

fun <P> createEmitter(): Emitter<P> = Emitter()
