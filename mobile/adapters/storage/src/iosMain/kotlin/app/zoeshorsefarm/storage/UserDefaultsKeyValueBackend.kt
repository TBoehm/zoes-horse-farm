package app.zoeshorsefarm.storage

import platform.Foundation.NSUserDefaults

/**
 * iOS persistence on `NSUserDefaults` (the app's preferences file). The system writes the values
 * to disk by itself, shortly after a change and when the app goes to the background, so a save
 * survives the app being killed. The API reports no write errors: a full disk is not detected.
 */
class UserDefaultsKeyValueBackend(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueBackend {
    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun setString(
        key: String,
        value: String,
    ) {
        defaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
