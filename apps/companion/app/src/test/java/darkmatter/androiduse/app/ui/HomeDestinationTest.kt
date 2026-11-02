package darkmatter.androiduse.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDestinationTest {
    @Test
    fun `app selector opens as a separate destination and back returns home`() {
        assertEquals(HomeDestination.ALLOWED_APPS, HomeDestination.HOME.openAllowedApps())
        assertEquals(HomeDestination.HOME, HomeDestination.ALLOWED_APPS.back())
        assertEquals(HomeDestination.HOME, HomeDestination.HOME.back())
    }
}
