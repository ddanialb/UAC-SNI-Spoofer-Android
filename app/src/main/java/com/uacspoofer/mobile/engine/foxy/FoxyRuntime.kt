package com.uacspoofer.mobile.engine.foxy

/**
 * The ports the *current* Foxy session really listens on.
 *
 * Callers used to assume `settings.socksPort`, but the relay can fall back to another port, so the
 * exit-IP lookup and the settings screen ended up dialling a port with nobody behind it and
 * reported "no information".
 */
object FoxyRuntime {
    @Volatile
    var socksPort: Int = 0

    @Volatile
    var httpPort: Int = 0

    val hasListener: Boolean get() = socksPort > 0
}
