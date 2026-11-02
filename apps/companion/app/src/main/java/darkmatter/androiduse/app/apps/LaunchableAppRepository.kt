package darkmatter.androiduse.app.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

data class LaunchableApp(val packageName: String, val label: String)

data class LauncherActivity(
    val packageName: String,
    val label: String?,
    val action: String,
    val categories: Set<String>,
)

object LauncherActions {
    const val MAIN = Intent.ACTION_MAIN
    const val LAUNCHER = Intent.CATEGORY_LAUNCHER
}

fun interface LauncherActivitySource {
    fun queryActivities(): List<LauncherActivity>
}

class LaunchableAppRepository(private val source: LauncherActivitySource) {
    constructor(queryActivities: () -> List<LauncherActivity>) : this(LauncherActivitySource(queryActivities))

    fun listAllowedLaunchableApps(allowedPackages: Set<String>): List<LaunchableApp> =
        listLaunchableApps().filter { it.packageName in allowedPackages }

    fun listLaunchableApps(): List<LaunchableApp> = source.queryActivities()
        .asSequence()
        .filter { it.packageName.isNotBlank() && it.action == LauncherActions.MAIN && LauncherActions.LAUNCHER in it.categories }
        .groupBy { it.packageName }
        .map { (packageName, activities) ->
            val label = activities.map { it.label?.trim().orEmpty() }
                .filter(String::isNotBlank)
                .minWithOrNull(String.CASE_INSENSITIVE_ORDER) ?: packageName
            LaunchableApp(packageName, label)
        }
        .sortedWith(Comparator { left, right ->
            val byLabel = String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label)
            if (byLabel != 0) byLabel else left.packageName.compareTo(right.packageName)
        })
        .toList()
}

class PackageManagerLauncherActivitySource(private val packageManager: PackageManager) : LauncherActivitySource {
    override fun queryActivities(): List<LauncherActivity> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return results.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            LauncherActivity(packageName, info.loadLabel(packageManager)?.toString(), Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER))
        }
    }
}

data class AllowedAppsSelection(val allowedPackages: Set<String>) {
    fun setAllowed(packageName: String, allowed: Boolean): AllowedAppsSelection =
        copy(allowedPackages = if (allowed) allowedPackages + packageName else allowedPackages - packageName)
}
