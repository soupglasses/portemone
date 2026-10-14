// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import androidx.annotation.DrawableRes

enum class BarcodeType(val format: com.google.zxing.BarcodeFormat, @param:androidx.annotation.StringRes val labelRes: Int) {
    NONE(com.google.zxing.BarcodeFormat.QR_CODE, R.string.barcode_plain),
    CODE_128(com.google.zxing.BarcodeFormat.CODE_128, R.string.barcode_code_128),
    EAN_13(com.google.zxing.BarcodeFormat.EAN_13, R.string.barcode_ean_13),
    QR_CODE(com.google.zxing.BarcodeFormat.QR_CODE, R.string.barcode_qr),
    PDF_417(com.google.zxing.BarcodeFormat.PDF_417, R.string.barcode_pdf417),
    AZTEC(com.google.zxing.BarcodeFormat.AZTEC, R.string.barcode_aztec),
    CODE_39(com.google.zxing.BarcodeFormat.CODE_39, R.string.barcode_code_39),
    CODE_93(com.google.zxing.BarcodeFormat.CODE_93, R.string.barcode_code_93),
    EAN_8(com.google.zxing.BarcodeFormat.EAN_8, R.string.barcode_ean_8),
    UPC_A(com.google.zxing.BarcodeFormat.UPC_A, R.string.barcode_upc_a),
    UPC_E(com.google.zxing.BarcodeFormat.UPC_E, R.string.barcode_upc_e),
    ITF(com.google.zxing.BarcodeFormat.ITF, R.string.barcode_itf),
    ITF_14(com.google.zxing.BarcodeFormat.ITF, R.string.barcode_itf_14),
    CODABAR(com.google.zxing.BarcodeFormat.CODABAR, R.string.barcode_codabar),
    DATA_MATRIX(com.google.zxing.BarcodeFormat.DATA_MATRIX, R.string.barcode_data_matrix);

    val square: Boolean get() = this == QR_CODE || this == AZTEC || this == DATA_MATRIX
}

internal fun barcodeEncodingHints(encoding: String): Map<com.google.zxing.EncodeHintType, Any> =
    mapOf(
        com.google.zxing.EncodeHintType.CHARACTER_SET to encoding,
        com.google.zxing.EncodeHintType.DATA_MATRIX_SHAPE to com.google.zxing.datamatrix.encoder.SymbolShapeHint.FORCE_SQUARE,
        com.google.zxing.EncodeHintType.DATA_MATRIX_COMPACT to true
    )

internal val BarcodeType.valueHintRes: Int
    get() = when (this) {
        BarcodeType.EAN_13 -> R.string.barcode_hint_13_digits_including_the_final_check_digit_keep
        BarcodeType.EAN_8, BarcodeType.UPC_E -> R.string.barcode_hint_8_digits_including_the_final_check_digit_keep
        BarcodeType.UPC_A -> R.string.barcode_hint_12_digits_including_the_final_check_digit_keep
        BarcodeType.ITF_14 -> R.string.barcode_hint_14_digits_including_the_final_check_digit_keep
        BarcodeType.ITF -> R.string.barcode_hint_an_even_number_of_digits_keep_leading_zeroes
        BarcodeType.CODABAR -> R.string.barcode_hint_digits_and_with_a_b_c_or_d
        else -> R.string.barcode_hint_leading_zeroes_and_spaces_are_kept_exactly_as
    }

internal fun BarcodeType.validateValue(value: String) {
    val digits = when (this) {
        BarcodeType.EAN_13 -> 13
        BarcodeType.EAN_8, BarcodeType.UPC_E -> 8
        BarcodeType.UPC_A -> 12
        BarcodeType.ITF_14 -> 14
        else -> null
    }
    if (digits != null) {
        require(value.length == digits && value.all { it in '0'..'9' }) {
            throw UserFacingException(R.string.error_needs_exactly_digits_including_the_check_digit, StringArgument(labelRes), digits)
        }
        // Require complete membership values instead of letting writers append missing digits.
        try {
            when (this) {
                BarcodeType.EAN_13 -> com.google.zxing.oned.EAN13Writer().encode(value)
                BarcodeType.EAN_8 -> com.google.zxing.oned.EAN8Writer().encode(value)
                BarcodeType.UPC_A -> com.google.zxing.oned.EAN13Writer().encode("0$value")
                BarcodeType.UPC_E -> com.google.zxing.oned.UPCEWriter().encode(value)
                BarcodeType.ITF_14 -> {
                    val sum = value.reversed().mapIndexed { index, digit ->
                        digit.digitToInt() * if (index % 2 == 0) 1 else 3
                    }.sum()
                    require(sum % 10 == 0)
                }

                else -> Unit
            }
        } catch (_: IllegalArgumentException) {
            throw UserFacingException(R.string.error_invalid_value_or_check_digit_check_the_number, StringArgument(labelRes))
        }
    }
    if (this == BarcodeType.ITF) {
        require(value.isNotEmpty() && value.length % 2 == 0 && value.all { it in '0'..'9' }) {
            throw UserFacingException(R.string.error_itf_needs_an_even_number_of_digits)
        }
    }
    if (this == BarcodeType.CODABAR) {
        require(
            value.length >= 3 && value.first() in "ABCD" && value.last() in "ABCD" &&
                    value.substring(1, value.lastIndex).all { it in "0123456789-$:/.+" }) {
            throw UserFacingException(R.string.error_codabar_needs_digits_or_between_start_and_end)
        }
    }
}

/** A disposable membership, deliberately separate from every retailer's real card. */
object TestCard {
    val card = Card(
        "temporary-test-card", "Test", "card", R.drawable.ic_card,
        0xFF146C64.toInt(), 0xFFFFFFFF.toInt(), BarcodeType.CODE_128, "1234567890", "1234567890"
    )
}

data class Card(
    val id: String,
    val brandName: String,
    val cardName: String,
    @param:DrawableRes val logo: Int,
    val brandColor: Int,
    val accentColor: Int,
    val barcodeType: BarcodeType,
    val rawValue: String,
    val displayText: String? = null,
    val stores: List<StoreLocation> = emptyList(),
    val expiresAt: String? = null,
    val voided: Boolean = false,
    val description: String? = null,
    val labelColor: Int = accentColor,
    val relevantAt: String? = null,
    val kind: CardKind = CardKind.MEMBERSHIP,
    val fields: List<CardField> = emptyList(),
    val source: CardSource? = null,
    val logoImage: String? = null,
    val artworkImage: String? = null,
    val barcodeEncoding: String = "UTF-8",
    val shaded: Boolean = false,
)

enum class CardKind(@param:androidx.annotation.StringRes val labelRes: Int) {
    MEMBERSHIP(R.string.cardkind_membership),
    GENERIC(R.string.cardkind_generic),
    COUPON(R.string.cardkind_coupon),
    EVENT(R.string.cardkind_event),
    BOARDING_PASS(R.string.cardkind_boarding_pass)
}
enum class CardFieldSection(@param:androidx.annotation.StringRes val labelRes: Int) {
    HEADER(R.string.cardfieldsection_header),
    PRIMARY(R.string.cardfieldsection_primary),
    SECONDARY(R.string.cardfieldsection_secondary),
    AUXILIARY(R.string.cardfieldsection_auxiliary),
    BACK(R.string.cardfieldsection_back)
}
data class CardField(
    val key: String,
    val label: String,
    val value: String,
    val section: CardFieldSection
)

/** External identity belongs to the source; card behavior remains source-independent. */
data class CardSource(
    val format: String,
    val issuerId: String,
    val recordId: String,
    val signatureValid: Boolean = false,
    val issuerTrusted: Boolean = false,
    val warnings: List<String> = emptyList()
)

const val MAX_CARD_LOCATIONS = 10

fun Card.isUsable(now: java.time.Instant = java.time.Instant.now()): Boolean =
    !voided && (expiresAt == null || now.isBefore(java.time.Instant.parse(expiresAt)))

internal fun Card.statusLabelRes(): Int? = when {
    voided -> R.string.card_status_no_longer_valid
    !isUsable() -> R.string.card_status_expired
    else -> null
}

internal fun validateCard(card: Card) {
    require(card.id.isNotBlank() && card.id != TestCard.card.id && card.id != SampleCard.card.id) { throw UserFacingException(R.string.error_invalid_card_identifier) }
    require(card.brandName.isNotBlank() && card.cardName.isNotBlank()) { throw UserFacingException(R.string.error_enter_a_brand_and_card_label) }
    require(card.source == null || (card.source.issuerId.isNotBlank() && card.source.recordId.isNotBlank())) { throw UserFacingException(R.string.error_invalid_import_identity) }
    require(card.expiresAt == null || runCatching { java.time.Instant.parse(card.expiresAt) }.isSuccess) { throw UserFacingException(R.string.error_choose_a_valid_expiration_date) }
    validateBarcode(card)
}

internal fun validateBarcode(card: Card) {
    require((card.barcodeType == BarcodeType.NONE || card.rawValue.isNotEmpty()) && card.rawValue.length <= 4096) { throw UserFacingException(R.string.error_enter_a_code_of_up_to_4096_characters) }
    require(runCatching { java.nio.charset.Charset.forName(card.barcodeEncoding) }.isSuccess) { throw UserFacingException(R.string.error_unsupported_barcode_encoding) }
    require(
        java.nio.charset.Charset.forName(card.barcodeEncoding).newEncoder().canEncode(card.rawValue)
    ) { throw UserFacingException(R.string.error_the_code_cannot_be_represented_in_this_encoding) }
    if (card.barcodeType == BarcodeType.NONE) return
    card.barcodeType.validateValue(card.rawValue)
    try {
        com.google.zxing.MultiFormatWriter().encode(
            card.rawValue,
            card.barcodeType.format,
            1,
            1,
            barcodeEncodingHints(card.barcodeEncoding)
        )
    } catch (_: Exception) {
        throw UserFacingException(R.string.error_this_value_cannot_be_encoded_as, StringArgument(card.barcodeType.labelRes))
    }
}

object SampleCard {
    // Demonstration data, not a redeemable membership.
    val card = Card(
        "sample-card", "Example", "Card", R.drawable.ic_card,
        0xFF146C64.toInt(), -1, BarcodeType.CODE_128,
        "1234567890123456", "1234 5678 9012 3456"
    )
}
