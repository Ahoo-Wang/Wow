import tempfile
import unittest
from pathlib import Path

import skill_lint


class SkillLintTest(unittest.TestCase):
    def test_reports_forbidden_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("Use Grep and ./gradlew domain:test with where { }", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(3, len(findings))
            messages = [finding.message for finding in findings]
            self.assertIn("Use rg or rg --files instead of Claude-style Grep/Glob wording.", messages)
            self.assertIn("Use resolved Gradle module placeholders instead of hard-coded domain/api modules.", messages)
            self.assertIn("Use Wow Query DSL condition/pagination APIs instead of where/page wording.", messages)

    def test_reports_missing_reference_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("See `references/missing.md` for details.", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual("Referenced local markdown file does not exist: references/missing.md", findings[0].message)

    def test_allows_negative_assert_that_guidance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("Use `.assert()` for assertions, not AssertJ's `assertThat()`.", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual([], findings)

    def test_reports_invalid_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text("{not json", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual("Invalid JSON file.", findings[0].message)

    def test_reports_source_drift_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "references" / "prepare-key.md"
            skill.parent.mkdir(parents=True)
            skill.write_text(
                "Use countQuery, Command-Wait-Timeout, PreparedValue(value, duration), @Enabled(properties = []), @get:Summary, and wow.compensation.host.",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(6, len(findings))
            messages = [finding.message for finding in findings]
            self.assertIn(
                "Use `Condition.count(queryService)` wording; Wow does not expose a countQuery DSL function.",
                messages,
            )
            self.assertIn(
                "Use the current source header spelling `Command-Wait-Timout`, and note the docs/source mismatch when relevant.",
                messages,
            )
            self.assertIn(
                "Use `value.toForever()` or `value.toTtlAt(ttlAt)`; PreparedValue is an interface, not a Duration constructor.",
                messages,
            )
            self.assertIn(
                "Do not document a generic `@Enabled` annotation unless it exists in the current Wow checkout.",
                messages,
            )
            self.assertIn(
                "Use property-level `@Summary`/`@Description`; current annotations do not target property getters.",
                messages,
            )
            self.assertIn(
                "Do not include deployment-only compensation properties in business-service skills; use Saga/Event handler `@Retry` guidance instead.",
                messages,
            )


if __name__ == "__main__":
    unittest.main()
