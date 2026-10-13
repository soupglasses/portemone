# SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
# SPDX-License-Identifier: EUPL-1.2

Apple root certificates for offline PKPass issuer-chain verification.

Apple Root CA: https://www.apple.com/appleca/AppleIncRootCertificate.cer
Apple Root CA - G2: https://www.apple.com/certificateauthority/AppleRootCA-G2.cer
Apple Root CA - G3: https://www.apple.com/certificateauthority/AppleRootCA-G3.cer
Published fingerprints: https://support.apple.com/105116

The PEM encodings were retrieved from the E2Encrypted certificate mirror and
verified byte-for-byte against Apple's published SHA-256 fingerprints. The
verifier independently pins the fingerprints when loading trust anchors.

This offline check does not perform OCSP or CRL revocation checks. Do not treat
an intact package and valid signature as proof a card can still be redeemed.
