import tempfile
import unittest
from pathlib import Path

import skill_lint


class SkillLintTest(unittest.TestCase):
    @staticmethod
    def write_skill(skill: Path, body: str, frontmatter: str | None = None) -> None:
        skill.parent.mkdir(parents=True)
        if frontmatter is None:
            frontmatter = f"name: {skill.parent.name}\ndescription: Test skill."
        skill.write_text(f"---\n{frontmatter}\n---\n{body}", encoding="utf-8")

    def test_reports_forbidden_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(skill, "Use Grep and ./gradlew domain:test with where { }")

            findings = skill_lint.lint(root)

            self.assertEqual(3, len(findings))
            messages = [finding.message for finding in findings]
            self.assertIn("Use rg or rg --files instead of Claude-style Grep/Glob wording.", messages)
            self.assertIn("Use resolved Gradle module placeholders instead of hard-coded domain/api modules.", messages)
            self.assertIn("Use Wow Query DSL condition/pagination APIs instead of where/page wording.", messages)

    def test_reports_colon_prefixed_gradle_module_placeholders(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(skill, "Run ./gradlew :api:test\nRun ./gradlew :domain:check")

            findings = skill_lint.lint(root)

            self.assertEqual(2, len(findings))
            self.assertEqual(
                [
                    "Use resolved Gradle module placeholders instead of hard-coded domain/api modules.",
                    "Use resolved Gradle module placeholders instead of hard-coded domain/api modules.",
                ],
                [finding.message for finding in findings],
            )

    def test_allows_legacy_wait_timeout_compatibility_guidance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "references" / "command-gateway.md"
            skill.parent.mkdir(parents=True)
            skill.write_text(
                "The legacy misspelled `Command-Wait-Timout` header remains accepted for compatibility.",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual([], findings)

    def test_reports_missing_reference_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(skill, "See `references/missing.md` for details.")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual("Referenced local markdown file does not exist: references/missing.md", findings[0].message)

    def test_allows_negative_assert_that_guidance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(skill, "Use `.assert()` for assertions, not AssertJ's `assertThat()`.")

            findings = skill_lint.lint(root)

            self.assertEqual([], findings)

    def test_negative_assert_that_guidance_does_not_skip_other_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(skill, "Use `.assert()`, not `assertThat()`. TODO remove placeholder.")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual("Resolve placeholders before shipping skill content.", findings[0].message)

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
                "Use countQuery, Command-Wait-Timout, PreparedValue(value, duration), @Enabled(properties = []), @get:Summary, wow.compensation.host, and **/settings.gradle.kts.",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(7, len(findings))
            messages = [finding.message for finding in findings]
            self.assertIn(
                "Use `Condition.count(queryService)` wording; Wow does not expose a countQuery DSL function.",
                messages,
            )
            self.assertIn(
                "Use the documented `Command-Wait-Timeout` header; the misspelled form is legacy compatibility only.",
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
            self.assertIn(
                'Use rg-native `-g "settings.gradle.kts"` filtering instead of shell globstar.',
                messages,
            )

    def test_reports_missing_skill_frontmatter(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("# Wow", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual("SKILL.md must start with YAML frontmatter.", findings[0].message)

    def test_reports_unexpected_skill_frontmatter_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(
                skill,
                "# Wow",
                frontmatter="name: wow\ndescription: Wow guidance.\ncompatibility: Kotlin 2.3+",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "SKILL.md frontmatter contains unsupported key: compatibility",
                findings[0].message,
            )

    def test_reports_skill_name_directory_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(
                skill,
                "# Wow",
                frontmatter="name: other-skill\ndescription: Wow guidance.",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "SKILL.md name must match its parent directory: wow",
                findings[0].message,
            )

    def test_reports_long_reference_without_contents(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reference = root / "skills" / "wow" / "references" / "long.md"
            reference.parent.mkdir(parents=True)
            reference.write_text("\n".join(["# Long Reference", *["content"] * 100]), encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "Reference files longer than 100 lines must include `## Contents` near the top.",
                findings[0].message,
            )

    def test_reports_malformed_skill_frontmatter_value(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(
                skill,
                "# Wow",
                frontmatter="name: wow\ndescription: [unterminated",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "SKILL.md frontmatter field must be a YAML string: description",
                findings[0].message,
            )

    def test_reports_non_string_skill_frontmatter_field(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(
                skill,
                "# Wow",
                frontmatter="name: wow\ndescription: true",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "SKILL.md frontmatter field must be a YAML string: description",
                findings[0].message,
            )

    def test_reports_reserved_plain_scalar_indicator(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(
                skill,
                "# Wow",
                frontmatter="name: wow\ndescription: @invalid",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "SKILL.md frontmatter field must be a YAML string: description",
                findings[0].message,
            )

    def test_reports_tab_indentation_in_block_scalar(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            self.write_skill(
                skill,
                "# Wow",
                frontmatter="name: wow\ndescription: |\n\tinvalid yaml indentation",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "SKILL.md frontmatter must use spaces, not tabs, for indentation.",
                findings[0].message,
            )

    def test_reports_missing_frontmatter_mapping_separator_whitespace(self):
        frontmatters = (
            "name:wow\ndescription: Test skill.",
            "name: wow\ndescription:test",
        )
        for frontmatter in frontmatters:
            with self.subTest(frontmatter=frontmatter), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                skill = root / "skills" / "wow" / "SKILL.md"
                self.write_skill(skill, "# Wow", frontmatter=frontmatter)

                findings = skill_lint.lint(root)

                self.assertEqual(1, len(findings))
                self.assertEqual(
                    "SKILL.md frontmatter mapping values require whitespace after `:`.",
                    findings[0].message,
                )


if __name__ == "__main__":
    unittest.main()
