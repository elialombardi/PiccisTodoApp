package com.elialombardi.piccistodo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.elialombardi.piccistodo.ui.screens.LoginContent
import org.junit.Rule
import org.junit.Test

class ServerUrlScreenTest {
    @get:Rule val compose = createComposeRule()
    private val label get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ui_server)

    private fun showLogin(loading: Boolean = false) {
        compose.setContent {
            var server by remember { mutableStateOf("https://first.example/") }
            MaterialTheme {
                LoginContent(server, "", "", loading, null, false,
                    { server = it }, {}, {}, {}, {})
            }
        }
    }

    @Test fun serverAddressCanBeEdited() {
        showLogin()
        compose.onNodeWithText(label).performTextReplacement("https://changed.example:8443")
        compose.onNodeWithText(label).assertTextContains("https://changed.example:8443")
    }

    @Test fun serverAddressIsDisabledDuringLogin() {
        showLogin(loading = true)
        compose.onNodeWithText(label).assertIsNotEnabled()
    }
}
