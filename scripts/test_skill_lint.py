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

    def test_reports_colon_prefixed_gradle_module_placeholders(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("Run ./gradlew :api:test\nRun ./gradlew :domain:check", encoding="utf-8")

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

    def test_negative_assert_that_guidance_does_not_skip_other_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("Use `.assert()`, not `assertThat()`. TODO remove placeholder.", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual("Resolve placeholders before shipping skill content.", findings[0].message)

    def test_allows_assert_that_in_java_fence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reference = root / "skills" / "wow" / "references" / "testing.md"
            reference.parent.mkdir(parents=True)
            reference.write_text(
                "```java\nassertThat(result).isEqualTo(expected);\n```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_reports_assert_that_in_kotlin_fence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reference = root / "skills" / "wow" / "references" / "testing.md"
            reference.parent.mkdir(parents=True)
            reference.write_text(
                "```kotlin\nassertThat(result).isEqualTo(expected)\n```\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(2, findings[0].line)
            self.assertEqual(
                "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
                findings[0].message,
            )

    def test_reports_invalid_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text("{not json", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual("Invalid JSON file.", findings[0].message)

    def test_reports_violation_after_multiline_eval_exemption(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{"expected_output": "The legacy spelling is retained for compatibility.\\n'
                'Send Command-Wait-Timout"}',
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "Use the documented `Command-Wait-Timeout` header; the misspelled form is legacy compatibility only.",
                findings[0].message,
            )

    def test_applies_assert_that_exemption_per_multiline_eval_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{"expected_output": "Use `.assert()`, not `assertThat()`.\\n'
                'assertThat(result).isEqualTo(expected)"}',
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(
                "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
                findings[0].message,
            )

    def test_allows_java_assert_that_in_eval_markdown_fence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{"expected_output": "```java\\n'
                'assertThat(result).isEqualTo(expected);\\n```"}',
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

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

    def test_reports_wow_api_drift_patterns_in_markdown_and_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reference = root / "skills" / "wow" / "references" / "testing.md"
            reference.parent.mkdir(parents=True)
            reference.write_text(
                "\n".join(
                    [
                        "`expectEventType<T>()`",
                        "`expectCommandType<T>()`",
                        "All DSL functions are in package `me.ahoo.wow.query.dsl`.",
                        "The value falls back to lowercased class name at runtime.",
                        "Check `CompensationFilter`.",
                        "Commands and domain events should include Wow API metadata annotations:",
                    ]
                ),
                encoding="utf-8",
            )
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text('{"expected_output": "use expectEventType {}"}', encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(7, len(findings))
            messages = [finding.message for finding in findings]
            self.assertEqual(3, messages.count("Use the actual type assertion APIs with a KClass/Class argument."))
            self.assertIn(
                "Distinguish query-builder DSL packages from backend-specific query execution extensions.",
                messages,
            )
            self.assertIn(
                "Describe AggregateRoute defaults and spaced behavior from the current OpenAPI implementation.",
                messages,
            )
            self.assertIn(
                "Use the concrete `EventCompensationFilter` type or describe the runtime boundary generically.",
                messages,
            )
            self.assertIn(
                "Require API metadata only when commands or events are part of the API/domain contract.",
                messages,
            )

    def test_reports_unclosed_markdown_fence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "```markdown\n# Template\n```text\nvalue\n```\n```\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(6, findings[0].line)
            self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

    def test_reports_unclosed_markdown_fence_in_blockquote(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "> ```kotlin\n> val value = 1\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(1, findings[0].line)
            self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

    def test_reports_unclosed_markdown_fence_in_list_item(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- ```kotlin\n  val value = 1\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(1, findings[0].line)
            self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

    def test_reports_unclosed_indented_fence_in_list_item(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- Example:\n\n    ```kotlin\n    val value = 1\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(3, findings[0].line)
            self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

    def test_allows_closed_markdown_fences_in_containers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "> ```kotlin\n> val quoted = 1\n> ```\n\n"
                "- ```kotlin\n  val listed = 1\n  ```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_allows_container_fence_markers_as_top_level_fence_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "```java\n> ```\n- ```\nassertThat(result).isEqualTo(expected);\n```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_reports_fences_closed_outside_blockquote_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "> ```kotlin\n> val value = 1\n```\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual([1, 3], [finding.line for finding in findings])
            self.assertTrue(
                all(finding.message == "Unclosed Markdown code fence." for finding in findings)
            )

    def test_reports_fences_closed_outside_list_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- ```kotlin\n  val value = 1\n```\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual([1, 3], [finding.line for finding in findings])
            self.assertTrue(
                all(finding.message == "Unclosed Markdown code fence." for finding in findings)
            )

    def test_reports_misindented_close_after_blockquote_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "> - ```kotlin\n>   val value = 1\n  > ```\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual([1, 3], [finding.line for finding in findings])
            self.assertTrue(
                all(finding.message == "Unclosed Markdown code fence." for finding in findings)
            )

    def test_allows_list_before_blockquote_fence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- > ```java\n"
                "  > assertThat(result).isEqualTo(expected);\n"
                "  > ```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_allows_adversarial_patterns_in_eval_prompt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{"evals": ['
                '{"prompt": "Migrate where { } and countQuery", "expected_output": "Use current APIs."},'
                '{"expected_output": "Use current APIs.", "prompt": "Migrate where { } and countQuery"}'
                "]}",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_allows_longer_outer_markdown_fence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "````markdown\n# Template\n- Example\n    ````\n```text\nvalue\n```\n````\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))


if __name__ == "__main__":
    unittest.main()
