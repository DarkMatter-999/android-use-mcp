package darkmatter.androiduse.app.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import darkmatter.androiduse.app.actions.AppLaunchPolicy

class AllowedAppsSelectionTest {
    @Test
    fun selectionAddsAndRemovesOnlyTheChosenLaunchablePackage() {
        val initial = AllowedAppsSelection(setOf("com.example.alpha"))

        val changed = initial.setAllowed("com.example.beta", true).setAllowed("com.example.alpha", false)

        assertEquals(setOf("com.example.beta"), changed.allowedPackages)
        assertEquals(setOf("com.example.alpha"), initial.allowedPackages)
    }

    @Test
    fun launchPolicyRequiresAnExactPhoneSelectedPackage() {
        assertFalse(AppLaunchPolicy.isAllowed("com.example.app", emptySet()))
        assertTrue(AppLaunchPolicy.isAllowed("com.example.app", setOf("com.example.app")))
        assertFalse(AppLaunchPolicy.isAllowed("Com.example.app", setOf("com.example.app")))
    }
}
