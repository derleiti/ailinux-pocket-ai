"""Pure RC-release evidence tests; no GitHub access or downloads needed."""
import importlib.util
import io
import json
import urllib.error
from pathlib import Path
import unittest
from unittest import mock
from tempfile import TemporaryDirectory

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

    def test_draft_preflight_requires_unpublished_state_and_real_assets(self):
        draft = release()
        draft.update(draft=True, published_at=None)
        apk, sidecar = audit.matching_assets(draft, "v2.1.0-RC17", prepublish=True)
        self.assertTrue(apk["name"].endswith(".apk"))
        self.assertTrue(sidecar["name"].endswith(".sha256"))
        with self.assertRaises(audit.AuditError):
            audit.matching_assets(draft, "v2.1.0-RC17")
        with self.assertRaises(audit.AuditError):
            audit.matching_assets(release(), "v2.1.0-RC17", prepublish=True)
        for mutation in (
            lambda r: r.update(assets=[]),
            lambda r: r.update(tag_name="v2.1.0-RC20"),
            lambda r: r.update(prerelease=False),
            lambda r: r["assets"][0].update(digest=None),
        ):
            item = release()
            item.update(draft=True, published_at=None)
            mutation(item)
            with self.assertRaises(audit.AuditError):
                audit.matching_assets(item, "v2.1.0-RC17", prepublish=True)

    def test_github_untagged_draft_asset_links_are_metadata_not_download_sources(self):
        draft = release()
        draft.update(draft=True, published_at=None)
        for obj in draft["assets"]:
            obj["browser_download_url"] = (
                "https://github.com/derleiti/ailinux-pocket-ai/releases/download/"
                "untagged-16cdfdaec6239aecafe0/" + obj["name"])
        audit.matching_assets(draft, "v2.1.0-RC17", prepublish=True)
        for mutation in (
            lambda r: r["assets"][0].update(
                browser_download_url="http://github.com/derleiti/ailinux-pocket-ai/"
                "releases/download/untagged-16cdfdaec6239aecafe0/" +
                r["assets"][0]["name"]),
            lambda r: r["assets"][0].update(
                browser_download_url="https://evil.example/releases/download/"
                "untagged-16cdfdaec6239aecafe0/" + r["assets"][0]["name"]),
            lambda r: r["assets"][0].update(
                browser_download_url="https://github.com/other/repo/releases/download/"
                "untagged-16cdfdaec6239aecafe0/" + r["assets"][0]["name"]),
        ):
            item = json.loads(json.dumps(draft))
            mutation(item)
            with self.assertRaises(audit.AuditError):
                audit.matching_assets(item, "v2.1.0-RC17", prepublish=True)

    def test_published_metadata_lookup_stays_on_exact_tag_endpoint(self):
        published = release()
        with mock.patch.object(audit.urllib.request, "urlopen",
                               return_value=io.BytesIO(json.dumps(published).encode())) as upstream:
            result = audit.github_metadata("v2.1.0-RC17")
            self.assertEqual(result["tag_name"], "v2.1.0-RC17")
            self.assertEqual(upstream.call_count, 1)
            req = upstream.call_args.args[0]
            self.assertTrue(req.full_url.endswith("/releases/tags/v2.1.0-RC17"))

    def test_github_404_draft_lookup_falls_back_to_exact_paginated_release_list(self):
        draft = release()
        draft.update(draft=True, published_at=None)
        first = urllib.error.HTTPError("https://api.github.com/releases/tags/a",
                                      404, "Not Found", {}, None)
        with mock.patch.object(audit.urllib.request, "urlopen",
                               side_effect=[first, io.BytesIO(json.dumps([draft]).encode())]) as upstream:
            got = audit.github_metadata("v2.1.0-RC17", prepublish=True)
            self.assertEqual(got["tag_name"], "v2.1.0-RC17")
            self.assertEqual(upstream.call_count, 2)
        with mock.patch.object(audit.urllib.request, "urlopen",
                               side_effect=[first, io.BytesIO(b"[]")]) as upstream:
            with self.assertRaisesRegex(audit.AuditError, "not accessible"):
                audit.github_metadata("v2.1.0-RC17", prepublish=True)
            self.assertEqual(upstream.call_count, 2)
        # Never silently use list fallback for public release verification.
        with mock.patch.object(audit.urllib.request, "urlopen", side_effect=first) as upstream:
            with self.assertRaisesRegex(audit.AuditError, "release-by-tag"):
                audit.github_metadata("v2.1.0-RC17")
            self.assertEqual(upstream.call_count, 1)

    def test_local_draft_bytes_never_accept_symlinks_or_size_mismatches(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "candidate.apk"
            target.write_bytes(b"test-model-apk")
            import hashlib
            expected = hashlib.sha256(b"test-model-apk").hexdigest()
            self.assertEqual(expected, audit.local_asset_sha256(target, 14, 4096))
            for length, cap in ((13, 4096), (14, 13), (0, 4096)):
                with self.assertRaises(audit.AuditError):
                    audit.local_asset_sha256(target, length, cap)
            link = root / "duplicate.apk"
            link.symlink_to(target)
            with self.assertRaises(audit.AuditError):
                audit.local_asset_sha256(link, 14, 4096)

    def test_preflight_rejects_stale_rc20_tag_before_remote_calls(self):
        with TemporaryDirectory() as directory:
            gradle = Path(directory) / "build.gradle.kts"
            gradle.write_text('versionName = "2.1.0-RC19"\nversionCode = 44\n')
            with mock.patch.object(audit, "github_metadata") as metadata:
                with self.assertRaisesRegex(audit.AuditError,
                                            "tag does not match Gradle"):
                    audit.audit("v2.1.0-RC20", gradle, audit.EXPECTED_CERT,
                                prepublish=True, metadata_only=True)
                metadata.assert_not_called()

    def test_preflight_metadata_only_never_downloads_and_requires_local_files_afterwards(self):
        with TemporaryDirectory() as directory:
            gradle = Path(directory) / "build.gradle.kts"
            gradle.write_text('versionName = "2.1.0-RC17"\nversionCode = 42\n')
            draft = release()
            draft.update(draft=True, published_at=None)
            with mock.patch.object(audit, "github_metadata", return_value=draft):
                with mock.patch.object(audit, "downloaded_sha256") as download:
                    result = audit.audit("v2.1.0-RC17", gradle,
                                         audit.EXPECTED_CERT, prepublish=True,
                                         metadata_only=True)
                    self.assertIn("Draft metadata valid", result)
                    download.assert_not_called()
                    with self.assertRaisesRegex(audit.AuditError, "needs --asset-dir"):
                        audit.audit("v2.1.0-RC17", gradle,
                                    audit.EXPECTED_CERT, prepublish=True)
                    with self.assertRaisesRegex(audit.AuditError, "must independently"):
                        audit.audit("v2.1.0-RC17", gradle,
                                    audit.EXPECTED_CERT, asset_dir=Path(directory))

    def test_full_draft_asset_preflight_checks_staged_hash_badging_and_signer(self):
        import hashlib
        with TemporaryDirectory() as directory:
            root = Path(directory)
            gradle = root / "build.gradle.kts"
            gradle.write_text('versionName = "2.1.0-RC17"\nversionCode = 42\n')
            candidate = root / "assets"
            candidate.mkdir()
            data = b"P" * (10 * 1024 * 1024 + 1)
            filename = "AILinux-Pocket-AI-2.1.0-RC17-arm64-debug.apk"
            apk = candidate / filename
            sidecar = candidate / (filename + ".sha256")
            apk.write_bytes(data)
            apk_hash = hashlib.sha256(data).hexdigest()
            sidecar.write_text(apk_hash + "  " + filename + "\n")
            sidecar_hash = hashlib.sha256(sidecar.read_bytes()).hexdigest()
            draft = release()
            draft.update(draft=True, published_at=None)
            draft["assets"][0].update(size=apk.stat().st_size, digest="sha256:" + apk_hash)
            draft["assets"][1].update(size=sidecar.stat().st_size,
                                       digest="sha256:" + sidecar_hash)
            def fake_android_verifier(command):
                if command[0].endswith("aapt"):
                    return "package: name='me.ailinux.pocket' versionCode='42' versionName='2.1.0-RC17'"
                if command[0].endswith("apksigner"):
                    return "Signer #1 certificate SHA-256 digest: " + audit.EXPECTED_CERT
                return ""
            with mock.patch.object(audit, "github_metadata", return_value=draft), \
                    mock.patch.object(audit, "run", side_effect=fake_android_verifier):
                message = audit.audit("v2.1.0-RC17", gradle, audit.EXPECTED_CERT,
                                      prepublish=True, asset_dir=candidate)
                self.assertIn("unpublished draft candidate", message)
                self.assertIn(apk_hash, message)
                # A changed byte or another filename must abort before publication.
                apk.write_bytes(b"X" + data[1:])
                with self.assertRaisesRegex(audit.AuditError, "digest differs"):
                    audit.audit("v2.1.0-RC17", gradle, audit.EXPECTED_CERT,
                                prepublish=True, asset_dir=candidate)
                apk.write_bytes(data)
                (candidate / "unexpected.txt").write_text("extra")
                with self.assertRaisesRegex(audit.AuditError, "two verified names"):
                    audit.audit("v2.1.0-RC17", gradle, audit.EXPECTED_CERT,
                                prepublish=True, asset_dir=candidate)

    def test_draft_workflow_is_manual_read_only_and_checks_exact_tag(self):
        workflow = (S.parents[1] / ".github/workflows/rc-release-preflight.yml").read_text()
        for expected in ("workflow_dispatch:", "contents: read", "git show",
                         "--metadata-only", "--prepublish", "--asset-dir",
                         "gh release download", "tagged-build.gradle.kts",
                         "permissions:\n      contents: write"):
            self.assertIn(expected, workflow)
        # Drafts require push access to list, even when just inspecting.
        # The manual preflight job only has the higher token scope.
        self.assertEqual(1, workflow.count("contents: write"))
        self.assertIn("persist-credentials: false", workflow)
        for forbidden in ("types: [published]", "gh release create",
                          "gh release upload", "gh release edit",
                          "git push"):
            self.assertNotIn(forbidden, workflow)

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
