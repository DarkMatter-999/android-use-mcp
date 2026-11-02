package darkmatter.androiduse.app.ui

/** The two top-level destinations in the companion's home flow. */
internal enum class HomeDestination {
    HOME,
    ALLOWED_APPS,
    ;

    fun openAllowedApps(): HomeDestination = ALLOWED_APPS

    fun back(): HomeDestination = when (this) {
        HOME -> HOME
        ALLOWED_APPS -> HOME
    }
}
