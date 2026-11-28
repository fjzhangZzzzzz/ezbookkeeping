package top.fjzhang233.books

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDeviceLabelTest {
    @Test fun nativeAndPowerShellSessionsUseTheirClientNames() {
        assertEquals("家庭账本 · Android 15", sessionDeviceLabel("FamilyBooks/0.1.13 (Android 15)"))
        assertEquals("PowerShell · Windows", sessionDeviceLabel("Mozilla/5.0 (Windows NT 10.0) PowerShell/7.5"))
    }

    @Test fun browserVariantsTakePrecedenceOverCompatibilityTokens() {
        assertEquals("Edge · Windows", sessionDeviceLabel("Mozilla/5.0 (Windows NT 10.0) Chrome/130 Safari/537 Edg/130"))
        assertEquals("Firefox · iOS 18.2", sessionDeviceLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) FxiOS/134 Safari/605"))
    }

    @Test fun missingDataDoesNotInventADevice() {
        assertEquals("未知客户端", sessionDeviceLabel(""))
        assertEquals("未知客户端", sessionDeviceLabel("custom-client/1.2"))
        assertEquals("Linux", sessionDeviceLabel("custom-client (Linux)"))
    }
}
