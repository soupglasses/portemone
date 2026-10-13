// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/** A visual picker and labelled sliders offer the same full range of opaque colors. */
@Composable
internal fun CardColorPicker(initialColor: Int, onDismiss: () -> Unit, onSelected: (Int) -> Unit) {
    val resources = LocalResources.current
    val initial =
        remember(initialColor) { FloatArray(3).also { AndroidColor.colorToHSV(initialColor, it) } }
    var hue by rememberSaveable { mutableFloatStateOf(initial[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initial[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initial[2]) }
    var hex by rememberSaveable {
        mutableStateOf(
            String.format(
                Locale.ROOT,
                "%06X",
                initialColor and 0xFFFFFF
            )
        )
    }
    val selected = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
    val valid = hex.removePrefix("#").matches(Regex("[0-9a-fA-F]{6}"))
    fun choose(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h; saturation = s; brightness = v
        hex = String.format(
            Locale.ROOT,
            "%06X",
            AndroidColor.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_color)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(selected))
                        .semantics { contentDescription = resources.getString(R.string.selected_color, hex) })
                Canvas(
                    Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp))
                        .pointerInput(hue) {
                            detectTapGestures { point ->
                                choose(
                                    s = (point.x / size.width).coerceIn(
                                        0f,
                                        1f
                                    ), v = (1f - point.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                        }
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                choose(
                                    s = (change.position.x / size.width).coerceIn(0f, 1f),
                                    v = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                                )
                            }
                        }) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                Color.White,
                                Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                            )
                        )
                    )
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val point = Offset(saturation * size.width, (1f - brightness) * size.height)
                    drawCircle(Color.Black, 7.dp.toPx(), point, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 7.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                }
                Text(stringResource(R.string.hue), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = hue, onValueChange = { choose(h = it) }, valueRange = 0f..360f,
                    modifier = Modifier.semantics { contentDescription = resources.getString(R.string.hue) })
                Text(stringResource(R.string.saturation), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = saturation,
                    onValueChange = { choose(s = it) },
                    modifier = Modifier.semantics { contentDescription = resources.getString(R.string.saturation) })
                Text(stringResource(R.string.brightness), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = brightness,
                    onValueChange = { choose(v = it) },
                    modifier = Modifier.semantics { contentDescription = resources.getString(R.string.brightness) })
                OutlinedTextField(
                    value = hex,
                    onValueChange = { value ->
                        hex = value
                        val digits = value.removePrefix("#")
                        if (digits.matches(Regex("[0-9a-fA-F]{6}"))) {
                            val hsv = FloatArray(3)
                            AndroidColor.colorToHSV((0xFF000000L or digits.toLong(16)).toInt(), hsv)
                            hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                        }
                    },
                    label = { Text(stringResource(R.string.hex_color)) },
                    prefix = { Text("#") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { if (!valid) Text(stringResource(R.string.enter_a_six_digit_hex_color_such_as_146c64)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSelected(
                        (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
                    )
                }) { Text(stringResource(R.string.use_color)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
