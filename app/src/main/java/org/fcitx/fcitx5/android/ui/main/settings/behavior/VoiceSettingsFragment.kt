/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.DashScopeClient
import java.util.concurrent.Executors

class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    private val bg = Executors.newSingleThreadExecutor()

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        Preference(requireContext()).apply {
            key = "voice_request_permission"
            setTitle(R.string.voice_request_permission)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                true
            }
            screen.addPreference(this)
        }
        Preference(requireContext()).apply {
            key = "voice_test_connection"
            setTitle(R.string.voice_test_connection)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                Toast.makeText(requireContext(), R.string.voice_testing, Toast.LENGTH_SHORT).show()
                val prefs = AppPrefs.getInstance().voice
                val endpoint = prefs.websocketEndpoint.getValue().trim()
                val apiKey = prefs.apiKey.getValue().trim()
                val model = prefs.model.getValue().trim()
                    .ifEmpty { "qwen-audio-3.0-asr-flash-streaming" }
                bg.execute {
                    val err = try {
                        DashScopeClient.probe(endpoint, apiKey, model)
                    } catch (e: Exception) {
                        e.message ?: "unknown"
                    }
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        if (err == null) {
                            Toast.makeText(requireContext(), R.string.voice_test_ok, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.voice_test_fail, err),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                true
            }
            screen.addPreference(this)
        }
    }

    @Deprecated("Use ActivityResult API")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                requireContext(),
                if (granted) R.string.enabled else R.string.voice_error_no_permission,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroy() {
        bg.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 1001
    }
}
