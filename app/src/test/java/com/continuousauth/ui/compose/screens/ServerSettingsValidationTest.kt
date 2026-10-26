package com.continuousauth.ui.compose.screens

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsValidationTest {
    @Test fun acceptsHostnamesAndIpAddressesWithSeparateValidPort() {
        listOf("ca.macrz.com", "https://ca.macrz.com", "http://192.168.1.10", "https://[::1]").forEach {
            assertNull(it, validateServerSettings(it, "443"))
        }
        assertNull(validateServerSettings("https://ca.macrz.com/", "65535"))
    }

    @Test fun rejectsEmptyAddressUnsupportedSchemesAndUrlExtras() {
        listOf("", " ", "bad host", "ftp://server.test", "https://user:secret@server.test", "https://server.test/path", "https://server.test?q=1", "https://server.test#fragment", "https://server.test:443").forEach {
            assertNotNull(it, validateServerSettings(it, "443"))
        }
    }

    @Test fun rejectsMissingOutOfRangeAndNonNumericPorts() {
        listOf("", "-1", "0", "65536", "443.5", "abcd", "999999999999999").forEach {
            assertNotNull(it, validateServerSettings("https://ca.macrz.com", it))
        }
    }
}
