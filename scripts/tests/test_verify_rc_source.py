"""Pure, local source-to-tag guard; no network or GitHub mutation."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'verify_rc_source.py'
spec = importlib.util.spec_from_file_location('rc_guard', SCRIPT)
import sys
sys.path.insert(0, str(SCRIPT.parent))
rc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rc)


class ReleaseSourceGuardTest(unittest.TestCase):
    def test_matching_incremented_version(self):
        text = 'versionName = "2.1.0-RC21"\nversionCode = 45'
        self.assertEqual(('2.1.0-RC21', 45),
                         rc.validate_source('v2.1.0-RC21', text, 44))

    def test_reject_rc20_tag_that_still_builds_rc19(self):
        with self.assertRaisesRegex(rc.AuditError, 'does not match'):
            rc.validate_source('v2.1.0-RC20',
                               'versionName = "2.1.0-RC19"\nversionCode = 44', 43)

    def test_reject_code_not_higher_than_previous(self):
        with self.assertRaisesRegex(rc.AuditError, 'increase'):
            rc.validate_source('v2.1.0-RC21',
                               'versionName = "2.1.0-RC21"\nversionCode = 44', 44)

    def test_reject_stable_tag_not_in_rc_pipeline(self):
        with self.assertRaises(rc.AuditError):
            rc.validate_source('v2.2.0',
                               'versionName = "2.2.0"\nversionCode = 45', 44)
