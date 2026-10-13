// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.content.res.Resources
import androidx.annotation.StringRes

/** Validation stays independent of the activity and the language used to display it. */
internal data class StringArgument(@param:StringRes val id: Int)

internal class UserFacingException(
    @param:StringRes val text: Int,
    vararg val arguments: Any,
) : IllegalArgumentException()

internal fun Throwable.userMessage(resources: Resources, @StringRes fallback: Int): String =
    if (this is UserFacingException) resources.getString(text, *arguments.map {
        if (it is StringArgument) resources.getString(it.id) else it
    }.toTypedArray())
    else resources.getString(fallback)
