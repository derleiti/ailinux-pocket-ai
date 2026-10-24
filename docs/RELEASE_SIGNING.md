# Pocket AI — Production signing (not yet activated)

Published RC test APKs use the AILinux development DEBUG certificate, so they
can update each other in place. GitHub CI debug artifacts use runner-generated
debug certificates and do NOT match the AILinux developer certificate.

## Production signing prerequisites

1. Generate/provision a dedicated private Android release key outside the
   repository with strong passwords and secure redundant backup.
2. Record the certificate SHA-256 fingerprint via keytool or apksigner
   independently, treating the fingerprint as public metadata.
3. Create a protected GitHub Environment named production with required
   reviewers and trusted-branch restrictions.
4. Put these values into Environment SECRETS:
   AILINUX_RELEASE_KEYSTORE_B64 (base64 of the keystore bytes),
   AILINUX_RELEASE_STORE_PASSWORD, AILINUX_RELEASE_KEY_ALIAS,
   AILINUX_RELEASE_KEY_PASSWORD.
5. Put AILINUX_RELEASE_CERT_SHA256 in Environment VARIABLES, containing
   the expected certificate fingerprint (64 hex digits).
6. Bump Android versionCode above the newest RC and choose a stable
   versionName such as 1.0.0 (not an RC suffix).
7. Manually run Android Production Signing workflow with that version.
   Download its private artifact, validate, install on a QA device, and
   distribute only after owner review.

The production workflow deliberately does NOT create a signing key or
publish a release automatically. Without the configured secrets, expected
version and fingerprint, the build must fail validation. The script
scripts/verify-production-apk.sh checks package/version, native libraries,
manifest extraction, signer fingerprint and rejection of Android Debug.

## Critical Android signing migration

Android will NOT install a production APK signed with a different
certificate over an existing debug RC with the same package name, even
with a higher versionCode. Before releasing to RC users, implement and
test a GGUF model export/import procedure or another supported data
migration. Removing the test app before exporting can destroy models in
app-private storage. Do not distribute a production build under the
debug signing certificate merely to avoid this migration.

## Local verification

Provide AILINUX_RELEASE_KEYSTORE_FILE and the three signing secrets from
a secure secret manager, not a committed file or shell history. Then build
the release variant. The verifier also requires
AILINUX_EXPECTED_VERSION_NAME and AILINUX_RELEASE_CERT_SHA256.
Until these steps and real device QA are complete, all RC builds stay
debug-signed prereleases, not production-certified APKs.
