import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Domain and application get time and randomness only through injected ports (CLAUDE.md).
 * The web app enforces this with ESLint (no-restricted-globals/properties); here a scan of the
 * Kotlin sources fails the build on direct clock or random access.
 */
abstract class ForbiddenCallsCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations =
            sources.asFileTree.matching { include("**/*.kt") }.files.sorted().flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    FORBIDDEN.firstOrNull { it.containsMatchIn(line) }?.let { "${file.path}:${index + 1}: $line" }
                }
            }
        report.get().asFile.writeText(violations.joinToString("\n"))
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Domain/application must use the injected Clock/Rng ports:\n" + violations.joinToString("\n"),
            )
        }
    }

    private companion object {
        val FORBIDDEN =
            listOf(
                Regex("""\bkotlin\.random\b"""),
                Regex("""\bRandom\s*[.(]"""),
                Regex("""\bClock\.System\b"""),
                Regex("""\bTimeSource\b"""),
                Regex("""\bcurrentTimeMillis\b"""),
                Regex("""\bnanoTime\b"""),
                Regex("""\bNSDate\b"""),
            )
    }
}
