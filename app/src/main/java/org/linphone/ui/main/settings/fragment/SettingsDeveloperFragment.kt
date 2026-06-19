/*
 * Copyright (c) 2010-2025 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.settings.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.linphone.R
import org.linphone.core.UnifiedPushReceiver
import org.linphone.databinding.SettingsDeveloperFragmentBinding
import org.linphone.ui.main.fragment.GenericMainFragment
import org.linphone.ui.main.settings.viewmodel.SettingsViewModel

@UiThread
class SettingsDeveloperFragment : GenericMainFragment() {
    private lateinit var binding: SettingsDeveloperFragmentBinding

    private lateinit var viewModel: SettingsViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsDeveloperFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        postponeEnterTransition()
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        observeToastEvents(viewModel)

        viewModel.installPushHelperEvent.observe(viewLifecycleOwner) {
            it.consume {
                showInstallPushHelperDialog()
            }
        }

        binding.setBackClickListener {
            goBack()
        }

        startPostponedEnterTransition()
    }

    private fun showInstallPushHelperDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_developer_unified_push_install_helper_title)
            .setMessage(R.string.settings_developer_unified_push_install_helper_message)
            .setPositiveButton(R.string.settings_developer_unified_push_install_helper_action) { _, _ ->
                openNtfyInstallPage()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openNtfyInstallPage() {
        val packageName = UnifiedPushReceiver.NTFY_PACKAGE
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))
        } catch (e: Exception) {
            // No app store handler (e.g. de-Googled device) -> open the F-Droid page in a browser.
            startActivity(
                Intent(Intent.ACTION_VIEW, "https://f-droid.org/packages/$packageName/".toUri())
            )
        }
    }

    override fun onPause() {
        viewModel.updateSharingServersUrl()
        viewModel.updatePushCompatibleDomainsList()

        super.onPause()
    }
}
