package darkmatter.androiduse.app.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLaunchPolicyTest {
    @Test fun allowsOnlyFixedPackageNamesAndDistinguishesInvalidFromDisallowed() {
        assertEquals(AppLaunchValidation.ALLOWED, AppLaunchPolicy.validate("com.android.settings"))
        assertEquals(AppLaunchValidation.INVALID, AppLaunchPolicy.validate("not a package"))
        assertEquals(AppLaunchValidation.NOT_ALLOWLISTED, AppLaunchPolicy.validate("com.example.anything"))
        assertEquals(AppLaunchValidation.NOT_ALLOWLISTED, AppLaunchPolicy.validate("com.android.settings.evil"))
    }
}
