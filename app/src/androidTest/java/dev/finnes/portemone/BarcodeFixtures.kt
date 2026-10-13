// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

internal fun barcodeFixture(type: BarcodeType): String = when (type) {
    BarcodeType.NONE -> ""
    BarcodeType.EAN_13 -> "5901234123457"
    BarcodeType.EAN_8 -> "96385074"
    BarcodeType.UPC_A -> "012345678905"
    BarcodeType.UPC_E -> "01234565"
    BarcodeType.ITF_14 -> "10012345000017"
    BarcodeType.CODABAR -> "A0012345B"
    else -> "0000012345"
}

internal fun barcodeDecodeHints(type: BarcodeType): Map<com.google.zxing.DecodeHintType, Any> =
    mapOf(
        com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(type.format),
        com.google.zxing.DecodeHintType.RETURN_CODABAR_START_END to true
    )
