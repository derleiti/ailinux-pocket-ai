"""Pure Python test; runs before Gradle and Android SDK installation."""
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check_merge_artifacts.py"
spec = importlib.util.spec_from_file_location("merge_guard", SCRIPT)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class MergeGuardTest(unittest.TestCase):
    def test_conflict_markers_detected(self):
        for text in ("<<<<<<< HEAD", "=======", ">>>>>>> feature/fix", "||||||| base"):
            self.assertEqual("unresolved Git conflict marker", guard.reason(text))

    def test_bare_branch_labels_detected(self):
        for text in ("main", "fix/pocket-rc18-mcp-architecture-guide-20261025",
                     "release/pocket-rc19-qa-20261025", "qa/merge-20261025"):
            self.assertEqual("bare Git branch label in source", guard.reason(text))

    def test_both_android_workflows_enforce_checks_before_gradle(self):
        for name in ("android.yml", "android-fast.yml"):
            workflow = (SCRIPT.parents[1] / ".github/workflows" / name).read_text()
            self.assertIn("Reject unresolved merge artifacts", workflow)
            self.assertIn("Release PR must target main", workflow)
            self.assertIn("startsWith(github.head_ref, 'release/pocket-')", workflow)
            self.assertLess(workflow.index("Reject unresolved merge artifacts"),
                            workflow.index("setup-gradle"))

    def test_legitimate_code_and_comments_not_flagged(self):
        for text in ('private val subject = Regex("\\\\bmcp\\\\b")',
                     'val branch = "main"',
                     '  // main is the default branch',
                     'assertEquals("release/a", actual)',
                     '    private fun main() {',
                     '== not a merge marker'):
            self.assertIsNone(guard.reason(text))


if __name__ == "__main__":
    unittest.main()
