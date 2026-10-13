// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import org.json.JSONObject

/** Resource names survive APK upgrades; resource integers do not. */
internal object CardStorageCodec {
    fun encode(card: Card): String = JSONObject().apply {
        put("brand", card.brandName); put("label", card.cardName)
        put("logo", "card")
        put("backgroundColor", card.brandColor); put("foregroundColor", card.accentColor)
        put("barcodeType", card.barcodeType.name); put("message", card.rawValue)
        put("altText", card.displayText ?: JSONObject.NULL)
        put("expiresAt", card.expiresAt ?: JSONObject.NULL); put(
        "voided",
        card.voided
    ); put("archived", card.archived)
        put("description", card.description ?: JSONObject.NULL); put("labelColor", card.labelColor)
        put("relevantAt", card.relevantAt ?: JSONObject.NULL); put("kind", card.kind.name)
        put("fields", org.json.JSONArray().apply {
            card.fields.forEach { field ->
                put(
                    JSONObject().put("key", field.key).put("label", field.label)
                        .put("value", field.value).put("section", field.section.name)
                )
            }
        })
        put("source", card.source?.let { source ->
            JSONObject().put("format", source.format).put("issuerId", source.issuerId)
                .put("recordId", source.recordId).put("signatureValid", source.signatureValid)
                .put("issuerTrusted", source.issuerTrusted)
                .put("warnings", org.json.JSONArray(source.warnings))
        } ?: JSONObject.NULL)
        put("logoImage", card.logoImage ?: JSONObject.NULL); put(
        "artworkImage",
        card.artworkImage ?: JSONObject.NULL
    )
        put("barcodeEncoding", card.barcodeEncoding)
        put("shaded", card.shaded)
    }.toString()

    fun decode(id: String, json: String, stores: List<StoreLocation>): Card {
        val value = JSONObject(json)
        return Card(
            id,
            value.getString("brand"),
            value.getString("label"),
            R.drawable.ic_card,
            value.getInt("backgroundColor"),
            value.getInt("foregroundColor"),
            BarcodeType.valueOf(value.getString("barcodeType")),
            value.getString("message"),
            if (value.isNull("altText")) null else value.getString("altText"),
            stores,
            expiresAt = value.optString("expiresAt").takeUnless { value.isNull("expiresAt") },
            voided = value.getBoolean("voided"),
            archived = value.getBoolean("archived"),
            description = value.optString("description").takeUnless { value.isNull("description") },
            labelColor = value.optInt("labelColor", value.getInt("foregroundColor")),
            relevantAt = value.optString("relevantAt").takeUnless { value.isNull("relevantAt") },
            kind = CardKind.valueOf(value.optString("kind", "MEMBERSHIP")),
            fields = value.optJSONArray("fields")?.let { fields ->
                (0 until fields.length()).map { i ->
                    val field = fields.getJSONObject(i)
                    CardField(
                        field.getString("key"),
                        field.getString("label"),
                        field.getString("value"),
                        CardFieldSection.valueOf(field.getString("section"))
                    )
                }
            }.orEmpty(),
            source = value.optJSONObject("source")?.let { source ->
                CardSource(
                    source.getString("format"),
                    source.getString("issuerId"),
                    source.getString("recordId"),
                    source.getBoolean("signatureValid"),
                    source.getBoolean("issuerTrusted"),
                    source.optJSONArray("warnings")
                        ?.let { warnings -> (0 until warnings.length()).map { warnings.getString(it) } }
                        .orEmpty()
                )
            },
            logoImage = value.optString("logoImage").takeUnless { value.isNull("logoImage") },
            artworkImage = value.optString("artworkImage")
                .takeUnless { value.isNull("artworkImage") },
            barcodeEncoding = value.optString("barcodeEncoding", "UTF-8"),
            shaded = value.optBoolean("shaded", false)
        )
    }
}
