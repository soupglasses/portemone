// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.imports

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.finnes.portemone.R

/** Names are persisted; resource IDs and translated messages can change between builds. */
internal enum class PassNotice(@param:StringRes val text: Int, val legacyText: String) {
    MISSING_MANIFEST(R.string.pass_notice_missing_manifest_package_integrity_cannot_be_checked, "Missing manifest. Package integrity cannot be checked."),
    MANIFEST_COVERAGE(R.string.pass_notice_manifest_does_not_cover_exactly_the_package_contents, "Manifest does not cover exactly the package contents. Files may have been added or removed."),
    CONTENT_MISMATCH(R.string.pass_notice_package_contents_do_not_match_the_manifest_this, "Package contents do not match the manifest. This pass may have been tampered with."),
    INVALID_MANIFEST(R.string.pass_notice_invalid_manifest_package_integrity_cannot_be_checked, "Invalid manifest. Package integrity cannot be checked."),
    UNSIGNED(R.string.pass_notice_unsigned_pass_the_issuer_and_contents_cannot_be, "Unsigned pass. The issuer and contents cannot be authenticated."),
    SIGNATURE_WITHOUT_MANIFEST(R.string.pass_notice_the_signature_cannot_be_verified_without_a_manifest, "The signature cannot be verified without a manifest."),
    CERTIFICATE_DATES(R.string.pass_notice_the_signing_certificate_is_expired_or_not_yet, "The signing certificate is expired or not yet valid."),
    IDENTITY_MISMATCH(R.string.pass_notice_pass_identity_does_not_match_the_signing_certificate, "Pass identity does not match the signing certificate."),
    PASS_TYPE_MISMATCH(R.string.pass_notice_the_certificate_s_pass_type_id_marker_is, "The certificate’s Pass Type ID marker is missing or does not match this pass."),
    CERTIFICATE_USAGE(R.string.pass_notice_the_signing_certificate_is_not_authorized_for_apple, "The signing certificate is not authorized for Apple Wallet passes."),
    INELIGIBLE_CERTIFICATE(R.string.pass_notice_the_certificate_is_not_an_eligible_pass_signing, "The certificate is not an eligible pass signing certificate."),
    UNTRUSTED_ISSUER(R.string.pass_notice_the_signature_is_valid_but_its_certificate_chain, "The signature is valid, but its certificate chain could not be verified against an Apple root. The issuer is unverified."),
    INVALID_SIGNATURE(R.string.pass_notice_invalid_or_unreadable_pkcs_7_signature_this_pass, "Invalid or unreadable PKCS#7 signature. This pass may have been tampered with."),
    MANUAL_UPDATES(R.string.pass_notice_issuer_updates_are_not_downloaded_automatically_import_a, "Issuer updates are not downloaded automatically. Import a newer file to update this pass."),
    NFC_UNSUPPORTED(R.string.pass_notice_nfc_is_preserved_in_the_original_file_but, "NFC is preserved in the original file but cannot be used by this app."),
    BEACONS_UNSUPPORTED(R.string.pass_notice_beacon_relevance_is_not_supported_saved_geographic_locations, "Beacon relevance is not supported. Saved geographic locations are used instead."),
    DATES_INFORMATION_ONLY(R.string.pass_notice_relevant_dates_are_shown_in_details_automatic_presentation, "Relevant dates are shown in details. Automatic presentation uses saved locations."),
    APPLE_APPS_UNSUPPORTED(R.string.pass_notice_associated_apple_apps_are_not_launched, "Associated Apple apps are not launched."),
    OFFLINE_VERIFICATION(R.string.pass_notice_certificate_revocation_is_not_checked_online_import_does, "Certificate revocation is not checked online. Import does not register this device with the issuer.");
}

internal fun String.localizedPassNotice(resources: Resources): String {
    val notice = PassNotice.entries.find { it.name == this || it.legacyText == this }
    return notice?.let { resources.getString(it.text) } ?: this
}
