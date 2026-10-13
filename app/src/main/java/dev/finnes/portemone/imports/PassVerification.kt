// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.imports

import dev.finnes.portemone.R
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal data class PassVerification(val signatureValid: Boolean, val issuerTrusted: Boolean, val warnings: List<String>) {
    val summaryRes: Int get() = when {
        warnings.isNotEmpty() -> R.string.pass_verification_import_needs_your_review
        issuerTrusted -> R.string.pass_verification_signature_and_issuer_verified_offline
        signatureValid -> R.string.pass_verification_signature_verified_issuer_unverified
        else -> R.string.pass_verification_signature_not_verified
    }
}

/** Verify the exact manifest bytes, then every package member, before parsing content. */
internal object PassVerifier {
    // Leaf policy: https://images.apple.com/certificateauthority/pdf/Apple_WWDR_CPS_v1.32.pdf
    // Apple's published SHA-256 fingerprints, never an issuer name supplied by a pass:
    // https://support.apple.com/105116
    private val appleRoots = setOf(
        "b0b1730ecbc7ff4505142c49f1295e6eda6bcaed7e2c68c5be91b5a11001f024",
        "c2b9b042dd57830e7d117dac55ac8ae19407d38e41d88f3215bc3a890444a050",
        "63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179",
    )
    private val provider = BouncyCastleProvider()
    fun verify(files: Map<String, ByteArray>): PassVerification {
        val warnings = mutableListOf<String>()
        val manifestBytes = files["manifest.json"]
        if (manifestBytes == null) warnings += PassNotice.MISSING_MANIFEST.name
        else try {
            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            val expected = files.keys - setOf("manifest.json", "signature")
            val listed = manifest.keys().asSequence().toSet()
            if (listed != expected) warnings += PassNotice.MANIFEST_COVERAGE.name
            if (expected.any { name ->
                val hash = manifest.opt(name)
                hash !is String || !hash.matches(Regex("[0-9a-fA-F]{40}")) || !sha1(files.getValue(name)).equals(hash, ignoreCase = true)
            }) warnings += PassNotice.CONTENT_MISMATCH.name
        } catch (_: Exception) { warnings += PassNotice.INVALID_MANIFEST.name }
        val signature = files["signature"]
        if (signature == null) return PassVerification(false, false, warnings + PassNotice.UNSIGNED.name)
        if (manifestBytes == null) return PassVerification(false, false, warnings + PassNotice.SIGNATURE_WITHOUT_MANIFEST.name)
        var valid = false
        var trusted = false
        try {
            val original = CMSSignedData(signature)
            require(original.isDetachedSignature) { "Signature must be detached." }
            val cms = CMSSignedData(CMSProcessableByteArray(manifestBytes), signature)
            val signers = cms.signerInfos.signers
            // Never rely on all()/verifySignatures() accepting an empty signer set.
            require(signers.size == 1) { "Expected exactly one signing certificate." }
            val signer = signers.single()
            val certificates = cms.certificates.getMatches(null).toList()
            require(certificates.size <= 16) { "Too many signing certificates." }
            val leaf = certificates.single { signer.sid.match(it) }
            val converter = JcaX509CertificateConverter().setProvider(provider)
            val certificate = converter.getCertificate(leaf)
            valid = signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(certificate.publicKey))
            require(valid) { "Invalid signature." }
            val converted = certificates.map(converter::getCertificate)
            if (runCatching { certificate.checkValidity() }.isFailure) warnings += PassNotice.CERTIFICATE_DATES.name
            val json = JSONObject(String(files.getValue("pass.json"), Charsets.UTF_8))
            val passId = leaf.subject.getRDNs(ASN1ObjectIdentifier("0.9.2342.19200300.100.1.1")).map { it.first.value.toString() }
            val teams = leaf.subject.getRDNs(BCStyle.OU).map { it.first.value.toString() }
            if (json.optString("passTypeIdentifier") !in passId || json.optString("teamIdentifier") !in teams)
                warnings += PassNotice.IDENTITY_MISMATCH.name
            val marker = leaf.getExtension(ASN1ObjectIdentifier("1.2.840.113635.100.6.1.16"))?.parsedValue
            if (marker == null || json.optString("passTypeIdentifier") !in markerStrings(marker))
                warnings += PassNotice.PASS_TYPE_MISMATCH.name
            if (certificate.extendedKeyUsage?.contains("1.2.840.113635.100.4.14") != true)
                warnings += PassNotice.CERTIFICATE_USAGE.name
            if (certificate.basicConstraints >= 0 || certificate.keyUsage?.firstOrNull() == false)
                warnings += PassNotice.INELIGIBLE_CERTIFICATE.name
            trusted = trustedAppleChain(certificate, converted)
            if (!trusted) warnings += PassNotice.UNTRUSTED_ISSUER.name
        } catch (_: Exception) {
            warnings += PassNotice.INVALID_SIGNATURE.name
        }
        return PassVerification(valid, trusted, warnings.distinct())
    }

    private fun markerStrings(value: org.bouncycastle.asn1.ASN1Encodable): List<String> = when (val primitive = value.toASN1Primitive()) {
        is org.bouncycastle.asn1.ASN1String -> listOf(primitive.string)
        is org.bouncycastle.asn1.ASN1Sequence -> primitive.toArray().flatMap(::markerStrings)
        else -> emptyList()
    }

    private fun trustedAppleChain(leaf: X509Certificate, supplied: List<X509Certificate>): Boolean = runCatching {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
        val bundled = PassVerifier::class.java.getResourceAsStream("/pkpass/apple-roots.pem")?.use {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(it).filterIsInstance<X509Certificate>()
        }.orEmpty()
        val roots = (supplied + system + bundled).filter { sha256(it.encoded) in appleRoots }.map { TrustAnchor(it, null) }.toSet()
        if (roots.isEmpty()) return@runCatching false
        val parameters = PKIXBuilderParameters(roots, X509CertSelector().apply { certificate = leaf }).apply {
            isRevocationEnabled = false
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(supplied)))
        }
        CertPathBuilder.getInstance("PKIX").build(parameters)
        true
    }.getOrDefault(false)

    private fun digest(algorithm: String, bytes: ByteArray) = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha1(bytes: ByteArray) = digest("SHA-1", bytes)
    private fun sha256(bytes: ByteArray) = digest("SHA-256", bytes)
}
