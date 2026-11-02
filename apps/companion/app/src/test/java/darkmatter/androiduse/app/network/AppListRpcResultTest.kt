package darkmatter.androiduse.app.network

import darkmatter.androiduse.app.apps.LaunchableApp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class AppListRpcResultTest {
    @Test
    fun encodesEmptyAppList() {
        assertEquals("{\"apps\":[]}", AppListRpcResult.encode(emptyList()).toString())
    }

    @Test
    fun encodesOnlyAppNamesAndPackageNames() {
        val result = AppListRpcResult.encode(listOf(LaunchableApp("com.example.calc", "Calculator")))

        assertEquals(
            JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                put("name", JsonPrimitive("Calculator"))
                put("packageName", JsonPrimitive("com.example.calc"))
            })),
            result["apps"],
        )
        assertEquals(setOf("apps"), result.keys)
    }
}
