package darkmatter.androiduse.app.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLaunchPolicyTest {
    @Test fun allowsOnlyPhoneSelectedPackagesAndDistinguishesInvalidFromDisallowed() {
        val selected = setOf("com.example.selected")
        assertEquals(AppLaunchValidation.ALLOWED, AppLaunchPolicy.validate("com.example.selected", selected))
        assertEquals(AppLaunchValidation.INVALID, AppLaunchPolicy.validate("not a package", selected))
        assertEquals(AppLaunchValidation.NOT_ALLOWLISTED, AppLaunchPolicy.validate("com.example.other", selected))
        assertEquals(AppLaunchValidation.NOT_ALLOWLISTED, AppLaunchPolicy.validate("com.example.selected.child", selected))
    }
}
