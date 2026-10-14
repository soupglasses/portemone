// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.imports

import dev.finnes.portemone.*
import dev.finnes.portemone.cards.CardStorageCodec
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERUTF8String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PKPassImporterTest {
    private fun parse(json: JSONObject) = PKPassImporter.parse(json) { "Location $it" }
    private fun read(input: java.io.InputStream) = PKPassImporter.read(input) { "Location $it" }

    @Test
    fun importedStoreRadiusUsesSupportedLimits() {
        for ((requested, expected) in listOf(
            10 to 50f,
            50 to 50f,
            150 to 150f,
            1000 to 1000f,
            5000 to 1000f
        )) {
            assertEquals(
                expected,
                parse(
                    json().put(
                        "maxDistance",
                        requested
                    )
                ).stores.single().radiusMeters
            )
        }
    }

    private fun json(style: String = "storeCard", format: String = "PKBarcodeFormatQR") =
        JSONObject().apply {
            put("formatVersion", 1); put("organizationName", "Example"); put(
            "description",
            "Rewards"
        )
            put("logoText", "My membership"); put("passTypeIdentifier", "pass.example.rewards")
            put("serialNumber", "0000123"); put("teamIdentifier", "EXAMPLE123")
            put("backgroundColor", "rgb(10, 20, 30)"); put("foregroundColor", "rgb(255,255,255)")
            put("labelColor", "rgb(100, 120, 140)")
            put("expirationDate", "2030-01-02T03:04:05+02:00")
            put("relevantDate", "2029-01-01T00:00:00Z")
            put(
                style, JSONObject().put(
                    "primaryFields", JSONArray().put(
                        JSONObject()
                            .put("key", "issuer-specific-42").put("label", "Account type")
                            .put("value", "Special member")
                    )
                )
                    .put(
                        "backFields",
                        JSONArray().put(
                            JSONObject().put("key", "terms").put("label", "Terms")
                                .put("value", "Keep this card")
                        )
                    )
            )
            put(
                "barcodes", JSONArray().put(
                    JSONObject().put("format", format).put("message", "000123456789")
                        .put("altText", "000 123 456 789").put("messageEncoding", "utf-8")
                )
            )
            put(
                "locations",
                JSONArray().put(
                    JSONObject().put("latitude", 55.0).put("longitude", 12.0)
                        .put("relevantText", "Example store")
                )
            )
        }

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return result.toByteArray()
    }

    private fun files(json: JSONObject = json()): MutableMap<String, ByteArray> =
        linkedMapOf("pass.json" to json.toString().toByteArray())

    private fun manifest(files: MutableMap<String, ByteArray>) {
        files["manifest.json"] = JSONObject().apply {
            files.filterKeys { it !in setOf("signature", "manifest.json") }
                .forEach { (name, data) ->
                    put(
                        name,
                        MessageDigest.getInstance("SHA-1").digest(data)
                            .joinToString("") { "%02x".format(it) })
                }
        }.toString().toByteArray()
    }

    private fun sign(
        files: MutableMap<String, ByteArray>,
        expired: Boolean = false,
        mismatch: Boolean = false
    ) {
        manifest(files)
        val provider = BouncyCastleProvider()
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val issuer =
            X500Name("C=US,O=Example,OU=EXAMPLE123,UID=${if (mismatch) "pass.other" else "pass.example.rewards"},CN=Test signer")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            issuer, BigInteger.ONE, Date(now - 86_400_000),
            Date(if (expired) now - 1000 else now + 86_400_000), issuer, key.public
        )
        builder.addExtension(
            ASN1ObjectIdentifier("1.2.840.113635.100.6.1.16"),
            false,
            DERUTF8String(if (mismatch) "pass.other" else "pass.example.rewards")
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, false,
            org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                org.bouncycastle.asn1.x509.KeyPurposeId.getInstance(
                    ASN1ObjectIdentifier("1.2.840.113635.100.4.14")
                )
            )
        )
        val signer =
            JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(key.private)
        val certificate = builder.build(signer)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
            )
                .build(
                    JcaContentSignerBuilder("SHA256withRSA").setProvider(provider)
                        .build(key.private), certificate
                )
        )
        generator.addCertificates(JcaCertStore(listOf(certificate)))
        files["signature"] = generator.generate(
            CMSProcessableByteArray(files.getValue("manifest.json")),
            false
        ).encoded
    }

    @Test
    fun allStylesAndFormatsNormalizeWithoutLosingStringsOrFields() {
        val styles = mapOf(
            "storeCard" to CardKind.MEMBERSHIP,
            "generic" to CardKind.GENERIC,
            "coupon" to CardKind.COUPON,
            "eventTicket" to CardKind.EVENT,
            "boardingPass" to CardKind.BOARDING_PASS
        )
        val formats = mapOf(
            "PKBarcodeFormatQR" to BarcodeType.QR_CODE,
            "PKBarcodeFormatCode128" to BarcodeType.CODE_128,
            "PKBarcodeFormatPDF417" to BarcodeType.PDF_417,
            "PKBarcodeFormatAztec" to BarcodeType.AZTEC
        )
        styles.forEach { (style, kind) ->
            formats.forEach { (format, type) ->
                val card = parse(json(style, format))
                assertEquals(kind, card.kind); assertEquals(type, card.barcodeType)
                assertEquals(
                    "000123456789",
                    card.rawValue
                ); assertNull(card.displayText); assertEquals(
                "Rewards\n\n000 123 456 789",
                card.description
            )
                assertEquals("UTF-8", card.barcodeEncoding); assertEquals(
                "0000123",
                card.source?.recordId
            )
                assertEquals("2030-01-02T01:04:05Z", card.expiresAt)
                assertEquals("issuer-specific-42", card.fields.first().key)
                assertEquals(CardFieldSection.BACK, card.fields.last().section)
                assertEquals(0xff64788c.toInt(), card.labelColor)
                assertEquals(LocationOrigin.IMPORTED, card.stores.single().origin)
            }
        }
    }

    @Test
    fun unsignedPassHasExplicitWarningsAndRetainsOriginalPackage() {
        val bytes = zip(files())
        val pass = read(bytes.inputStream())
        assertArrayEquals(bytes, pass.bytes)
        assertFalse(pass.verification.signatureValid)
        assertTrue(pass.verification.warnings.any { it == PassNotice.UNSIGNED.name })
        assertEquals(pass.verification.warnings, pass.card.source?.warnings)
    }

    @Test
    fun detachedSignatureIsVerifiedButSelfSignedIssuerIsNotTrusted() {
        val files = files(); sign(files)
        val result = PassVerifier.verify(files)
        assertTrue(result.signatureValid); assertFalse(result.issuerTrusted)
        assertTrue(result.warnings.any { it == PassNotice.UNTRUSTED_ISSUER.name })
        assertFalse(result.warnings.any { it == PassNotice.IDENTITY_MISMATCH.name || it == PassNotice.CONTENT_MISMATCH.name })
    }

    @Test
    fun modifiedAssetAndAdditionalFilesAreDetectedEvenWithIntactSignature() {
        val files = files(); files["icon.png"] = byteArrayOf(1, 2, 3); sign(files)
        files["icon.png"] = byteArrayOf(4, 5, 6); files["extra.txt"] = byteArrayOf(7)
        val result = PassVerifier.verify(files)
        assertTrue(result.signatureValid)
        assertTrue(result.warnings.any { it == PassNotice.CONTENT_MISMATCH.name })
        assertTrue(result.warnings.any { it == PassNotice.MANIFEST_COVERAGE.name })
    }

    @Test
    fun modifiedManifestAndMissingSignatureAreDetected() {
        val files = files(); sign(files)
        files["manifest.json"] = "{}".toByteArray()
        assertFalse(PassVerifier.verify(files).signatureValid)
        files.remove("signature")
        assertTrue(PassVerifier.verify(files).warnings.any { it == PassNotice.UNSIGNED.name })
    }

    @Test
    fun certificateExpiryAndIssuerIdentityMismatchAreSeparateWarnings() {
        val files = files(); sign(files, expired = true, mismatch = true)
        val result = PassVerifier.verify(files)
        assertTrue(result.signatureValid)
        assertTrue(result.warnings.any { it == PassNotice.CERTIFICATE_DATES.name })
        assertTrue(result.warnings.any { it == PassNotice.IDENTITY_MISMATCH.name })
    }

    @Test
    fun cmsWithoutSignersCannotBeAcceptedAsSigned() {
        val files = files(); manifest(files)
        files["signature"] = CMSSignedDataGenerator().generate(
            CMSProcessableByteArray(files.getValue("manifest.json")),
            false
        ).encoded
        assertFalse(PassVerifier.verify(files).signatureValid)
    }

    @Test
    fun localizedFieldsAreTranslatedButMachineReadableValueIsPreserved() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("da-DK"))
            val files = files()
            files["da.lproj/pass.strings"] =
                "\"Special member\" = \"Særligt medlem\";\n\"000123456789\" = \"Wrong code\";".toByteArray()
            val card = read(zip(files).inputStream()).card
            assertEquals("Særligt medlem", card.fields.first().value)
            assertEquals("000123456789", card.rawValue)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun invalidOrOversizedInputsCannotBeOverridden() {
        val locations = JSONArray().apply {
            repeat(11) {
                put(
                    JSONObject().put("latitude", 55).put("longitude", 12)
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                json().put(
                    "locations",
                    locations
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            read(zip(files().apply {
                put(
                    "../pass.json",
                    byteArrayOf(0)
                )
            }).inputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            read(zip(files().apply {
                put(
                    "bomb.txt",
                    ByteArray(3 * 1024 * 1024)
                )
            }).inputStream())
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                json().put(
                    "barcodes", JSONArray().put(
                        JSONObject()
                            .put("format", "PKBarcodeFormatQR").put("message", 123)
                            .put("messageEncoding", "UTF-8")
                    )
                )
            )
        }
    }

    @Test
    fun directoryEntriesCannotBypassExpansionLimits() {
        val withDirectory = files().apply { put("assets/", byteArrayOf()) }
        assertEquals(
            "Example",
            read(zip(withDirectory).inputStream()).card.brandName
        )
        for (size in listOf(1, 20 * 1024 * 1024)) {
            val packageBytes = zip(files().apply { put("assets/", ByteArray(size)) })
            assertTrue(packageBytes.size < 8 * 1024 * 1024)
            assertThrows(IllegalArgumentException::class.java) {
                read(packageBytes.inputStream())
            }
        }
    }

    @Test
    fun passWithoutBarcodeKeepsUsefulFieldsAndValidity() {
        val json = json().apply { remove("barcodes"); put("voided", true) }
        val card = parse(json)
        assertEquals(BarcodeType.NONE, card.barcodeType)
        assertEquals(2, card.fields.size)
        assertFalse(card.isUsable())
    }

    @Test
    fun storageRoundTripRetainsNormalizedSourceIndependentData() {
        val original = parse(json()).copy(archived = true)
        val restored =
            CardStorageCodec.decode(original.id, CardStorageCodec.encode(original), original.stores)
        assertEquals(original, restored)
        assertFalse(restored.isUsable(Instant.parse("2028-01-01T00:00:00Z")))
        assertFalse(restored.copy(archived = false).isUsable(Instant.parse("2031-01-01T00:00:00Z")))
    }
}
