// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.Intent
import android.os.Bundle

/**
 * AOSP Settings and Samsung's SettingsPreferenceFragment use these hints for
 * their search-result highlight. Keep the public destination action: firmware
 * that ignores these internal extras still opens the normal settings page.
 * https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android16-release/src/com/android/settings/SettingsPreferenceFragment.java
 */
internal fun Intent.highlightSettingsPreference(key: String): Intent = apply {
    putExtra(":settings:fragment_args_key", key)
    putExtra(":settings:show_fragment_args", Bundle().apply {
        putString(":settings:fragment_args_key", key)
    })
}
