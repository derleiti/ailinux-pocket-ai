"""Pure RC-release evidence tests; no GitHub access or downloads needed."""
import importlib.util
from pathlib import Path
import unittest

S = Path(__file__).resolve().parents[1] / "audit_rc_release.py"
spec = importlib.util.spec_from_file_location("release_audit", S)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def release(tag="v2.1.0-RC17"):
    filename = "AILinux-Pocket-AI-2.1.0-RC17-arm64-debug.apk"
    base = "https://github.com/derleiti/ailinux-pocket-ai/releases/download/" + tag + "/"
    return {
        "tag_name": tag, "draft": False, "prerelease": True,
        "published_at": "2026-10-25T15:05:59Z",
        "assets": [
            {"name": filename, "size": 79_000_000, "digest": "sha256:" + "a" * 64,
             "state": "uploaded", "browser_download_url": base + filename},
            {"name": filename + ".sha256", "size": 111, "digest": "sha256:" + "b" * 64,
             "state": "uploaded", "browser_download_url": base + filename + ".sha256"},
        ],
    }


class RcAuditTest(unittest.TestCase):
    def test_reject_bad_tag_or_tag_version_mismatch(self):
        self.assertEqual("2.1.0-RC17", audit.version_of("v2.1.0-RC17"))
        for tag in ("v2.1.0-RC20-draft", "v2.1.0-RC20/evil", "v2.1.0-RC",
                    "v2.1.0-RC19-recovery1"):
            with self.assertRaises(audit.AuditError):
                audit.version_of(tag)
        self.assertEqual(("2.1.0-RC19", 44),
                         audit.gradle_version('versionName = "2.1.0-RC19"\nversionCode = 44'))
        with self.assertRaises(audit.AuditError):
            audit.gradle_version('versionName = "2.1.0-RC19"\nversionCode = 0')
        with self.assertRaises(audit.AuditError):
            audit.gradle_version('versionName = "2.1.0-RC19"\nversionName = "2.1.0-RC20"\nversionCode = 44')

    def test_valid_release_metadata(self):
        apk, sha = audit.matching_assets(release(), "v2.1.0-RC17")
        self.assertTrue(apk["name"].endswith(".apk"))
        self.assertTrue(sha["name"].endswith(".apk.sha256"))

    def test_fail_empty_releases_and_wrong_tags(self):
        for change in (
            lambda r: r.update(assets=[]),
            lambda r: r.update(tag_name="v2.1.0-RC20"),
            lambda r: r.update(draft=True),
            lambda r: r.update(prerelease=False),
            lambda r: r.update(published_at=None),
            lambda r: r["assets"].append(r["assets"][0].copy()),
            lambda r: r["assets"][0].update(digest="sha256:wrong"),
            lambda r: r["assets"][0].update(size=2),
            lambda r: r["assets"][1].update(browser_download_url="https://evil.test/file"),
        ):
            item = release()
            change(item)
            with self.assertRaises(audit.AuditError):
                audit.matching_assets(item, "v2.1.0-RC17")

    def test_published_release_workflow_is_read_only_and_checks_tagged_source(self):
        workflow = (S.parents[1] / ".github/workflows/rc-release-audit.yml").read_text()
        for expected in ('types: [published]', 'workflow_dispatch:',
                         'permissions:', 'contents: read',
                         'git show', 'scripts/audit_rc_release.py',
                         'tagged-build.gradle.kts'):
            self.assertIn(expected, workflow)
        self.assertNotIn('gh release create', workflow)
        self.assertNotIn('gh release upload', workflow)
        self.assertNotIn('contents: write', workflow)

    def test_checksum_is_explicit_exact_asset_name(self):
        filename = "AILinux-Pocket-AI-2.1.0-RC17-arm64-debug.apk"
        sample = ("c" * 64 + "  " + filename).encode()
        self.assertEqual("c" * 64, audit.sidecar_sha256(sample, filename))
        for bad in ("c" * 64 + "  other.apk", "c" * 63 + "  " + filename,
                    "c" * 64 + "  " + filename + "\n" + "d" * 64 + "  other.apk"):
            with self.assertRaises(audit.AuditError):
                audit.sidecar_sha256(bad.encode(), filename)

    def test_signer_and_version_must_match_original(self):
        line = "package: name='me.ailinux.pocket' versionCode='42' versionName='2.1.0-RC17'"
        audit.verify_badging(line, "2.1.0-RC17", 42)
        for version, code in (("2.1.0-RC18", 42), ("2.1.0-RC17", 43)):
            with self.assertRaises(audit.AuditError):
                audit.verify_badging(line, version, code)
        expected = audit.EXPECTED_CERT
        audit.verify_signer("Signer #1 certificate SHA-256 digest: " + expected, expected)
        with self.assertRaises(audit.AuditError):
            audit.verify_signer("Signer #1 certificate SHA-256 digest: " + "0" * 64, expected)


if __name__ == "__main__":
    unittest.main()
