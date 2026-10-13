// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.imports

import dev.finnes.portemone.*

import org.json.JSONObject
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipInputStream

/** Reads an untrusted package without extracting files or contacting its web service. */
internal object PKPassImporter {
    private const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_JSON_BYTES = 256 * 1024

    data class Package(
        val card: Card,
        val verification: PassVerification,
        val bytes: ByteArray,
        val limitations: List<String>
    )

    fun read(input: InputStream, locale: java.util.Locale = java.util.Locale.getDefault(), locationName: (Int) -> String): Package {
        val bytes = input.use { stream ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                require(result.size() + size <= MAX_PACKAGE_BYTES) { throw UserFacingException(R.string.error_this_pass_package_is_too_large) }
                result.write(buffer, 0, size)
            }
            result.toByteArray()
        }
        val files = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        var entries = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 128) { throw UserFacingException(R.string.error_too_many_files_in_this_pass) }
                val name = entry.name
                require(
                    !name.startsWith("/") && '\\' !in name && name.split('/')
                        .none { it == ".." || it == "." }) { throw UserFacingException(R.string.error_invalid_package_path) }
                if (entry.isDirectory) {
                    // closeEntry() inflates unread data, so never drain an unbounded directory.
                    require(zip.read() == -1) { throw UserFacingException(R.string.error_pass_directories_must_be_empty) }
                    zip.closeEntry(); continue
                }
                require(name !in files) { throw UserFacingException(R.string.error_duplicate_file_in_pass_package) }
                val content = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val limit =
                    if (name.endsWith(".json") || name.endsWith(".strings")) MAX_JSON_BYTES else 2 * 1024 * 1024
                while (true) {
                    val size = zip.read(buffer)
                    if (size < 0) break
                    expanded += size
                    require(expanded <= MAX_PACKAGE_BYTES * 2L && content.size() + size <= limit) { throw UserFacingException(R.string.error_this_pass_package_expands_beyond_the_supported_size) }
                    content.write(buffer, 0, size)
                }
                files[name] = content.toByteArray()
            }
        }
        require("pass.json" in files) { throw UserFacingException(R.string.error_the_file_does_not_contain_pass_json) }
        val verification = PassVerifier.verify(files)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(files.getValue("pass.json"))).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw UserFacingException(R.string.error_the_pass_description_is_not_valid_utf_8)
        }
        val raw = JSONObject(text)
        val localized = localize(raw, files, locale)
        val limitations = buildList {
            if (raw.has("webServiceURL")) add(PassNotice.MANUAL_UPDATES.name)
            if (raw.has("nfc")) add(PassNotice.NFC_UNSUPPORTED.name)
            if (raw.has("beacons")) add(PassNotice.BEACONS_UNSUPPORTED.name)
            if (raw.has("relevantDate") || raw.has("relevantDates")) add(PassNotice.DATES_INFORMATION_ONLY.name)
            if (raw.has("associatedStoreIdentifiers") || raw.has("appLaunchURL")) add(PassNotice.APPLE_APPS_UNSUPPORTED.name)
            add(PassNotice.OFFLINE_VERIFICATION.name)
        }
        val card = parse(localized, locationName).copy(
            logoImage = image(files, "logo", locale) ?: image(files, "icon", locale),
            artworkImage = image(files, "strip", locale) ?: image(files, "thumbnail", locale) ?: image(
                files,
                "background", locale
            ),
            source = CardSource(
                "pkpass", raw.getString("passTypeIdentifier"), raw.getString("serialNumber"),
                verification.signatureValid, verification.issuerTrusted, verification.warnings
            )
        )
        return Package(card, verification, bytes, limitations)
    }

    private fun image(files: Map<String, ByteArray>, name: String, locale: java.util.Locale): String? {
        val prefixes = listOf(
            "${locale.toLanguageTag()}.lproj/",
            "${locale.language}_${locale.country}.lproj/",
            "${locale.language}.lproj/",
            ""
        )
        val selected = prefixes.firstOrNull { prefix ->
            listOf(
                "${name}@3x.png",
                "${name}@2x.png",
                "$name.png"
            ).any { prefix + it in files }
        } ?: return null
        val value = files["${selected}${name}@3x.png"] ?: files["${selected}${name}@2x.png"]
        ?: files["${selected}$name.png"] ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(value, 0, value.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 4_000_000) { throw UserFacingException(R.string.error_the_pass_contains_invalid_or_oversized_artwork) }
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }.first {
                maxOf(
                    bounds.outWidth,
                    bounds.outHeight
                ) / it <= 256
            }
        }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(value, 0, value.size, options)
            ?: throw UserFacingException(R.string.error_the_pass_artwork_could_not_be_read)
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        return java.util.Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun localize(json: JSONObject, files: Map<String, ByteArray>, locale: java.util.Locale): JSONObject {
        val bytes = files["${locale.toLanguageTag()}.lproj/pass.strings"]
            ?: files["${locale.language}_${locale.country}.lproj/pass.strings"]
            ?: files["${locale.language}.lproj/pass.strings"] ?: return json
        val text =
            if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                        (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))
            ) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
        val uncommented =
            Regex("""("(?:\\.|[^"\\])*")|(/\*[\s\S]*?\*/|//[^\r\n]*)""").replace(text) {
                if (it.groups[1] != null) it.value else ""
            }
        val strings =
            Regex(""""((?:\\.|[^"\\])*)"\s*=\s*"((?:\\.|[^"\\])*)"\s*;""").findAll(uncommented)
                .associate {
                    fun unescape(value: String) = decodeLocalizedString(value)
                    unescape(it.groupValues[1]) to unescape(it.groupValues[2])
                }

        fun translateObject(value: JSONObject) {
            for (key in value.keys().asSequence().toList()) {
                when (val child = value.get(key)) {
                    is JSONObject -> translateObject(child)
                    is org.json.JSONArray -> for (i in 0 until child.length()) (child.opt(i) as? JSONObject)?.let(
                        ::translateObject
                    )

                    is String -> if (key in setOf(
                            "organizationName",
                            "description",
                            "logoText",
                            "label",
                            "value",
                            "relevantText",
                            "altText"
                        )
                    ) strings[child]?.let { value.put(key, it) }
                }
            }
        }
        translateObject(json)
        return json
    }

    private fun decodeLocalizedString(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character != '\\' || index == value.length) {
                append(character); continue
            }
            when (val escaped = value[index++]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'U', 'u' -> {
                    require(index + 4 <= value.length) { throw UserFacingException(R.string.error_invalid_localized_string) }
                    append(value.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }

                else -> append(escaped)
            }
        }
    }

    internal fun parse(json: JSONObject, locationName: (Int) -> String): Card {
        require(json.opt("formatVersion") is Number && json.getDouble("formatVersion") == 1.0) { throw UserFacingException(R.string.error_this_pass_format_version_is_not_supported) }
        require(
            listOf(
                "storeCard",
                "generic",
                "coupon",
                "eventTicket",
                "boardingPass"
            ).count { json.optJSONObject(it) != null } == 1) { throw UserFacingException(R.string.error_the_pass_must_contain_one_supported_pass_style) }
        listOf(
            "teamIdentifier",
            "passTypeIdentifier",
            "serialNumber",
            "organizationName",
            "description"
        ).forEach { key ->
            require(
                json.opt(key) is String && json.getString(key).isNotBlank()
            ) { throw UserFacingException(R.string.error_the_pass_is_missing_a_valid, key) }
        }
        require(
            json.getString("teamIdentifier").isNotBlank()
        ) { throw UserFacingException(R.string.error_the_pass_has_no_issuer_identifier) }
        val barcodes = json.optJSONArray("barcodes")
        val candidates =
            if (barcodes != null) (0 until barcodes.length()).map { barcodes.getJSONObject(it) }
            else listOfNotNull(json.optJSONObject("barcode"))
        val barcode = candidates.firstOrNull {
            it.optString("format") in listOf(
                "PKBarcodeFormatQR",
                "PKBarcodeFormatCode128",
                "PKBarcodeFormatPDF417",
                "PKBarcodeFormatAztec"
            )
        }
        require(candidates.isEmpty() || barcode != null) { throw UserFacingException(R.string.error_this_pass_has_an_unsupported_barcode_format) }
        val message = barcode?.get("message") ?: ""
        require(message is String) { throw UserFacingException(R.string.error_the_pass_barcode_message_must_be_a_string) }
        val id = UUID.randomUUID().toString()
        val locations = json.optJSONArray("locations")
        require(
            (locations?.length() ?: 0) <= MAX_CARD_LOCATIONS
        ) { throw UserFacingException(R.string.error_each_card_can_have_up_to_10_locations_detail) }
        val radius = if (json.has("maxDistance")) json.getDouble("maxDistance").toFloat() else 150f
        require(radius.isFinite() && radius > 0f) { throw UserFacingException(R.string.error_the_pass_has_an_invalid_location_radius) }
        val stores = (0 until (locations?.length() ?: 0)).map { index ->
            val location = locations!!.getJSONObject(index)
            val lat = location.getDouble("latitude")
            val lon = location.getDouble("longitude")
            require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0) { throw UserFacingException(R.string.error_the_pass_contains_an_invalid_location) }
            StoreLocation(
                UUID.nameUUIDFromBytes(
                    "${json.getString("passTypeIdentifier")}/${
                        json.getString(
                            "serialNumber"
                        )
                    }/$lat/$lon/$index".toByteArray(Charsets.UTF_8)
                ).toString(),
                location.optString("relevantText").ifBlank { locationName(index + 1) },
                lat,
                lon,
                radius.coerceIn(StoreRadiusRange),
                LocationOrigin.IMPORTED
            )
        }
        val expiration = if (json.has("expirationDate")) {
            try {
                OffsetDateTime.parse(json.getString("expirationDate")).toInstant().toString()
            } catch (_: Exception) {
                throw UserFacingException(R.string.error_the_pass_has_an_invalid_expiration_date)
            }
        } else null
        // Issuer prose belongs to the normalized description, not the code caption.
        val description = listOfNotNull(
            json.getString("description"),
            barcode?.optString("altText")?.takeIf { it.isNotBlank() && it != message })
            .distinct().joinToString("\n\n")
        val card = Card(
            id,
            json.getString("organizationName"),
            json.optString("logoText").ifBlank { json.getString("description") },
            R.drawable.ic_card,
            color(json, "backgroundColor", 0xFF146C64.toInt()),
            color(json, "foregroundColor", 0xFFFFFFFF.toInt()),
            when (barcode?.getString("format")) {
                null -> BarcodeType.NONE
                "PKBarcodeFormatQR" -> BarcodeType.QR_CODE
                "PKBarcodeFormatPDF417" -> BarcodeType.PDF_417
                "PKBarcodeFormatAztec" -> BarcodeType.AZTEC
                else -> BarcodeType.CODE_128
            },
            message,
            stores = stores,
            expiresAt = expiration,
            voided = json.optBoolean("voided", false),
            description = description,
            labelColor = color(json, "labelColor", 0xFFFFFFFF.toInt()),
            relevantAt = json.optString("relevantDate").takeIf { it.isNotBlank() }
                ?: json.optJSONArray("relevantDates")?.let { dates ->
                    (0 until dates.length()).joinToString("; ") { i ->
                        val date = dates.getJSONObject(i)
                        date.optString("date")
                            .ifBlank { "${date.optString("startDate")} – ${date.optString("endDate")}" }
                    }
                },
            kind = when {
                json.has("storeCard") -> CardKind.MEMBERSHIP
                json.has("coupon") -> CardKind.COUPON
                json.has("eventTicket") -> CardKind.EVENT
                json.has("boardingPass") -> CardKind.BOARDING_PASS
                else -> CardKind.GENERIC
            },
            fields = fields(json),
            source = CardSource(
                "pkpass",
                json.getString("passTypeIdentifier"),
                json.getString("serialNumber")
            ),
            barcodeEncoding = java.nio.charset.Charset.forName(
                barcode?.optString(
                    "messageEncoding",
                    "ISO-8859-1"
                ) ?: "UTF-8"
            ).name()
        )
        validateCard(card)
        return card
    }

    private fun fields(json: JSONObject): List<CardField> {
        val style = listOf(
            "storeCard",
            "generic",
            "coupon",
            "eventTicket",
            "boardingPass"
        ).first { json.has(it) }
        val content = json.getJSONObject(style)
        val groups = listOf(
            "headerFields" to CardFieldSection.HEADER,
            "primaryFields" to CardFieldSection.PRIMARY,
            "secondaryFields" to CardFieldSection.SECONDARY,
            "auxiliaryFields" to CardFieldSection.AUXILIARY,
            "backFields" to CardFieldSection.BACK
        )
        return groups.flatMap { (key, section) ->
            val array = content.optJSONArray(key)
            require((array?.length() ?: 0) <= 100) { throw UserFacingException(R.string.error_too_many_pass_fields) }
            (0 until (array?.length() ?: 0)).map { index ->
                val field = array!!.getJSONObject(index)
                val value = field.get("value")
                require(value is String || value is Number || value is Boolean) { throw UserFacingException(R.string.error_unsupported_pass_field_value) }
                CardField(
                    field.getString("key"),
                    field.optString("label"),
                    value.toString(),
                    section
                )
            }
        }
    }

    private fun color(json: JSONObject, key: String, fallback: Int): Int {
        if (!json.has(key)) return fallback
        val match =
            Regex("rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)").matchEntire(
                json.getString(key)
            )
                ?: throw UserFacingException(R.string.error_the_pass_contains_an_invalid_color)
        val channels = match.groupValues.drop(1).map(String::toInt)
        require(channels.all { it in 0..255 }) { throw UserFacingException(R.string.error_the_pass_contains_an_invalid_color) }
        return (0xFF shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }
}
