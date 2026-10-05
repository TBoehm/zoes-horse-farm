package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLifecycleTest {
    private class Setup {
        val calls = mutableListOf<String>()
        val lifecycle = ManualAppLifecycle()
        val off =
            bindCrashGuardLifecycle(lifecycle, onBackground = { calls += "background" }, onForeground = {
                calls +=
                    "resume"
            })
    }

    @Test
    fun `marks the guard clean when the app goes to the background and back when it returns`() {
        val s = Setup()
        s.lifecycle.update(AppState.BACKGROUND)
        s.lifecycle.update(AppState.FOREGROUND)
        assertEquals(listOf("background", "resume"), s.calls)
    }

    @Test
    fun `reports the same state only once`() {
        val s = Setup()
        s.lifecycle.update(AppState.FOREGROUND)
        s.lifecycle.update(AppState.BACKGROUND)
        s.lifecycle.update(AppState.BACKGROUND)
        assertEquals(listOf("background"), s.calls)
    }

    @Test
    fun `an app that starts in the background resumes on the first foreground`() {
        val lifecycle = ManualAppLifecycle(AppState.BACKGROUND)
        val calls = mutableListOf<String>()
        bindCrashGuardLifecycle(
            lifecycle,
            onBackground = { calls += "background" },
            onForeground = { calls += "resume" },
        )
        assertEquals(AppState.BACKGROUND, lifecycle.state)
        lifecycle.update(AppState.FOREGROUND)
        assertEquals(listOf("resume"), calls)
    }

    @Test
    fun `removes its listener`() {
        val s = Setup()
        s.off()
        s.lifecycle.update(AppState.BACKGROUND)
        assertEquals(emptyList(), s.calls)
    }

    @Test
    fun `a listener may unsubscribe while the state is delivered`() {
        val lifecycle = ManualAppLifecycle()
        val seen = mutableListOf<AppState>()
        var off: () -> Unit = {}
        off =
            lifecycle.onChange { state ->
                seen += state
                off()
            }
        lifecycle.update(AppState.BACKGROUND)
        lifecycle.update(AppState.FOREGROUND)
        assertEquals(listOf(AppState.BACKGROUND), seen)
    }
}
