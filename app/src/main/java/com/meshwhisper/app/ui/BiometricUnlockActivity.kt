package com.meshwhisper.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.meshwhisper.app.security.BiometricAuthManager

/**
 * Isolated FragmentActivity solely for BiometricPrompt authentication.
 * Keeps MainActivity as a clean ComponentActivity to prevent FragmentActivity's
 * legacy 16-bit requestCode limitation (Can only use lower 16 bits for requestCode)
 * from conflicting with Compose rememberLauncherForActivityResult.
 */
class BiometricUnlockActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BiometricAuthManager.promptAuthenticate(
            activity = this,
            onSuccess = {
                setResult(Activity.RESULT_OK)
                finish()
            },
            onError = { errorMsg ->
                val data = Intent().apply {
                    putExtra(EXTRA_ERROR_MESSAGE, errorMsg)
                }
                setResult(Activity.RESULT_CANCELED, data)
                finish()
            }
        )
    }

    companion object {
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
    }
}
