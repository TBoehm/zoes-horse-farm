package app.zoeshorsefarm.storage

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.SaveEnv
import app.zoeshorsefarm.application.SettingsSection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileKeyValueBackendTest {
    private val dir: Path = Files.createTempDirectory("zhf-storage-test")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun `stores reads and removes strings`() {
        val backend = FileKeyValueBackend(dir)
        assertNull(backend.getString("zoes-horse-farm.save"))
        backend.setString("zoes-horse-farm.save", "{\"a\":1}")
        assertEquals("{\"a\":1}", backend.getString("zoes-horse-farm.save"))
        backend.remove("zoes-horse-farm.save")
        assertNull(backend.getString("zoes-horse-farm.save"))
    }

    @Test
    fun `a new backend on the same folder sees the data`() {
        FileKeyValueBackend(dir).setString("k", "v")
        assertEquals("v", FileKeyValueBackend(dir).getString("k"))
    }

    @Test
    fun `keys with path characters stay inside the folder`() {
        val backend = FileKeyValueBackend(dir)
        backend.setString("../../escape", "x")
        backend.setString("a/b", "y")
        assertEquals("x", backend.getString("../../escape"))
        assertEquals("y", backend.getString("a/b"))
        Files.list(dir).use { files -> assertTrue(files.allMatch { it.parent == dir }) }
    }

    @Test
    fun `overwriting leaves no temporary files behind`() {
        val backend = FileKeyValueBackend(dir)
        repeat(3) { backend.setString("k", "v$it") }
        assertEquals("v2", backend.getString("k"))
        Files.list(dir).use { files -> assertEquals(1, files.count()) }
    }

    @Test
    fun `keeps unicode text exactly`() {
        val backend = FileKeyValueBackend(dir)
        backend.setString("k", "Zoë ❤ Blitz")
        assertEquals("Zoë ❤ Blitz", backend.getString("k"))
    }

    @Test
    fun `writing into a folder that cannot exist throws so the store can report it`() {
        val blocker = Files.createFile(dir.resolve("file"))
        val backend = FileKeyValueBackend(blocker.resolve("sub"))
        assertFailsWith<java.io.IOException> { backend.setString("k", "v") }
    }

    @Test
    fun `a local store on files survives a restart`() {
        val env = SaveEnv(Language.DE)
        LocalStore(FileKeyValueBackend(dir), env = env).update(SettingsSection) { it.copy(lang = Language.EN) }
        val again = LocalStore(FileKeyValueBackend(dir), env = env)
        assertEquals(Language.EN, again.get(SettingsSection).lang)
        assertTrue(again.canSave)
    }
}
