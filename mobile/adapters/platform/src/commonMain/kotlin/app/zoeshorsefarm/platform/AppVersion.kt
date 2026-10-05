package app.zoeshorsefarm.platform

/** The version shown when the build injected none (web: `"dev"`). */
const val DEV_VERSION = "dev"

/**
 * The build's version text (rule 58): [injected] is what the app shell reads from its build
 * (Android `versionName`, iOS `CFBundleShortVersionString`), null or blank in a development run.
 */
fun appVersionOf(injected: String?): String = injected?.takeIf { it.isNotBlank() } ?: DEV_VERSION
