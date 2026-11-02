package darkmatter.androiduse.app.network

import darkmatter.androiduse.app.apps.LaunchableApp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object AppListRpcResult {
    fun encode(apps: List<LaunchableApp>): JsonObject = buildJsonObject {
        put("apps", JsonArray(apps.map { app ->
            buildJsonObject {
                put("name", JsonPrimitive(app.label))
                put("packageName", JsonPrimitive(app.packageName))
            }
        }))
    }
}
