package darkmatter.androiduse.app.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchableAppRepositoryTest {
    @Test
    fun includesOnlyLauncherActivitiesAndCollapsesPackages() {
        val repository = LaunchableAppRepository {
            listOf(
                LauncherActivity("com.example.alpha", "Alpha", LauncherActions.MAIN, setOf(LauncherActions.LAUNCHER)),
                LauncherActivity("com.example.alpha", "Alpha secondary", LauncherActions.MAIN, setOf(LauncherActions.LAUNCHER)),
                LauncherActivity("com.example.service", "Service", "android.intent.action.VIEW", setOf(LauncherActions.LAUNCHER)),
                LauncherActivity("com.example.other", "Other", LauncherActions.MAIN, emptySet()),
            )
        }

        assertEquals(listOf(LaunchableApp("com.example.alpha", "Alpha")), repository.listLaunchableApps())
    }

    @Test
    fun fallsBackToPackageAndSortsNamesDeterministically() {
        val repository = LaunchableAppRepository {
            listOf(
                LauncherActivity("com.zed", " ", LauncherActions.MAIN, setOf(LauncherActions.LAUNCHER)),
                LauncherActivity("com.beta", "Beta", LauncherActions.MAIN, setOf(LauncherActions.LAUNCHER)),
                LauncherActivity("com.alpha", null, LauncherActions.MAIN, setOf(LauncherActions.LAUNCHER)),
            )
        }

        assertEquals(
            listOf(LaunchableApp("com.beta", "Beta"), LaunchableApp("com.alpha", "com.alpha"), LaunchableApp("com.zed", "com.zed")),
            repository.listLaunchableApps(),
        )
    }
}
