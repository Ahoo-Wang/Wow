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

    def test_allows_passive_negative_assert_that_guidance(self):
        guidance_lines = [
            "`assertThat()` should not be used in Kotlin tests.",
            "`assertThat(result)` should not be used in Kotlin tests.",
            "`assertThat(actual.orElse(null))` should not be used in Kotlin tests.",
            "AssertJ's `assertThat()` must not be called in Kotlin tests.",
            "`assertThat()` is not allowed in Kotlin tests.",
            "AssertJ `assertThat(value)` is not allowed in Kotlin tests.",
            "`assertThat()` is not permitted in Kotlin tests.",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            for guidance in guidance_lines:
                with self.subTest(guidance=guidance):
                    skill.write_text(guidance, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

    def test_passive_guidance_does_not_exempt_other_assert_that_occurrences(self):
        lines = [
            (
                "`assertThat(first)` should not be used; "
                "call `assertThat(second)` instead."
            ),
            (
                "`assertThat(first)` should not be used while "
                "`assertThat(second)` remains recommended."
            ),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            for line in lines:
                with self.subTest(line=line):
                    skill.write_text(line, encoding="utf-8")
                    findings = skill_lint.lint(root)

                    self.assertEqual(1, len(findings))
                    self.assertEqual(
                        "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
                        findings[0].message,
                    )

    def test_passive_guidance_requires_assert_that_api_prohibition(self):
        lines = [
            "`assertThat()` is not allowed to fail.",
            "The result of `assertThat()` should not be used as a boolean.",
            "The result of `assertThat()` must not be used to make decisions.",
            "The result of `assertThat()` should not be used in boolean contexts.",
            "The return value of `assertThat()` should not be used.",
            "The value returned by `assertThat()` is not allowed in this context.",
            "The output of `assertThat()` should not be used.",
            "The value of `assertThat()` should not be used.",
            "The result from `assertThat()` should not be used.",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            for line in lines:
                with self.subTest(line=line):
                    skill.write_text(line, encoding="utf-8")
                    findings = skill_lint.lint(root)

                    self.assertEqual(1, len(findings))
                    self.assertEqual(
                        "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
                        findings[0].message,
                    )

    def test_allows_multiple_negative_assert_that_occurrences(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text(
                "Avoid `assertThat(first)`; `assertThat(second)` should not be used.",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_allows_direct_passive_subject_after_context_or_list_prefix(self):
        guidance_lines = [
            "In Kotlin tests, `assertThat()` should not be used.",
            "- `assertThat()` should not be used in Kotlin tests.",
            "> AssertJ's `assertThat()` must not be called in Kotlin tests.",
            "# `assertThat()` should not be used in Kotlin tests.",
            "### AssertJ `assertThat()` must not be called in Kotlin tests.",
            "**`assertThat()`** should not be used in Kotlin tests.",
            "- [ ] `assertThat()` should not be used in Kotlin tests.",
            "The `assertThat()` method should not be used in Kotlin tests.",
            "**The `assertThat()` method** should not be used in Kotlin tests.",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            for guidance in guidance_lines:
                with self.subTest(guidance=guidance):
                    skill.write_text(guidance, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

    def test_negative_assert_that_guidance_does_not_skip_other_patterns(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("Use `.assert()`, not `assertThat()`. TODO remove placeholder.", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual("Resolve placeholders before shipping skill content.", findings[0].message)

    def test_negative_assert_that_guidance_does_not_match_unrelated_not(self):
        lines = [
            "assertThat(result).isNotNull() // result must not be null",
            "assertThat(result) should not be null",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            for line in lines:
                with self.subTest(line=line):
                    skill.write_text(line, encoding="utf-8")
                    findings = skill_lint.lint(root)

                    self.assertEqual(1, len(findings))
                    self.assertEqual(
                        "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
                        findings[0].message,
                    )

    def test_allows_natural_negative_assert_that_guidance(self):
        guidance_lines = [
            "不要在 Kotlin 测试中使用 `assertThat(result)`。",
            "不要在 Kotlin 测试中使用 AssertJ 的 `assertThat(result)`。",
            "Avoid calling `assertThat(result)` in Kotlin tests.",
            "Never rely on AssertJ `assertThat(result)` in Kotlin tests.",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            for guidance in guidance_lines:
                with self.subTest(guidance=guidance):
                    skill.write_text(guidance, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

    def test_negative_assert_that_guidance_does_not_cross_clause_boundaries(self):
        lines = [
            "不要修改状态，调用 assertThat(result) 验证结果。",
            "不要使用 MockK；然后调用 assertThat(result) 验证结果。",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            for line in lines:
                with self.subTest(line=line):
                    skill.write_text(line, encoding="utf-8")
                    findings = skill_lint.lint(root)
                    self.assertEqual(1, len(findings))
                    self.assertEqual(
                        "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
                        findings[0].message,
                    )

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

    def test_maps_multiple_expected_outputs_on_same_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{\n  "evals": [{"expected_output": "safe"}, '
                '{"expected_output": "Use countQuery"}]\n}',
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(2, findings[0].line)
            self.assertEqual(
                "Use `Condition.count(queryService)` wording; Wow does not expose a countQuery DSL function.",
                findings[0].message,
            )

    def test_ignores_expected_output_text_inside_json_string_for_line_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{\n  "note": "\\"expected_output\\": fake",\n'
                '  "evals": [{"expected_output": "Use countQuery"}]\n}',
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(3, findings[0].line)
            self.assertEqual(
                "Use `Condition.count(queryService)` wording; Wow does not expose a countQuery DSL function.",
                findings[0].message,
            )

    def test_non_string_expected_output_does_not_shift_line_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{\n  "expected_output": null,\n'
                '  "nested": {"expected_output": "Use countQuery"}\n}',
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(3, findings[0].line)
            self.assertEqual(
                "Use `Condition.count(queryService)` wording; Wow does not expose a countQuery DSL function.",
                findings[0].message,
            )

    def test_duplicate_expected_output_keys_keep_source_line_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{\n  "expected_output": "safe",\n'
                '  "expected_output": "Use countQuery"\n}',
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(3, findings[0].line)
            self.assertEqual(
                "Use `Condition.count(queryService)` wording; Wow does not expose a countQuery DSL function.",
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

    def test_reports_unclosed_markdown_fence_in_eval_expected_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evals = root / "skills" / "wow" / "evals" / "evals.json"
            evals.parent.mkdir(parents=True)
            evals.write_text(
                '{"expected_output": "```java\\n'
                'assertThat(result).isEqualTo(expected);"}',
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(1, findings[0].line)
            self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

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

    def test_frontmatter_literal_fence_is_not_scanned_as_markdown(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            skill = root / "skills" / "wow" / "SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text(
                "---\n"
                "name: wow\n"
                "description: |\n"
                "  ```\n"
                "  Use countQuery\n"
                "---\n"
                "# Wow\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(5, findings[0].line)
            self.assertEqual(
                "Use `Condition.count(queryService)` wording; Wow does not expose a countQuery DSL function.",
                findings[0].message,
            )

    def test_allows_backtick_in_backtick_fence_info_as_plain_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text("```java`example\n", encoding="utf-8")

            self.assertEqual([], skill_lint.lint(root))

    def test_allows_backtick_in_tilde_fence_info(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text("~~~java`example\n", encoding="utf-8")

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(1, findings[0].line)
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

    def test_allows_tab_indented_fence_in_list_item(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "-\t```kotlin\n\tval value = 1\n\t```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_does_not_open_fence_after_excess_list_marker_padding(self):
        padded_fences = [
            "-     ```kotlin\n",
            "-\t\t```kotlin\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for padded_fence in padded_fences:
                with self.subTest(padded_fence=padded_fence):
                    template.write_text(padded_fence, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

    def test_ignores_fence_markers_inside_raw_html_blocks(self):
        raw_html_blocks = [
            "<!--\n```\n-->\n",
            "<script>\n```\n</script>\n",
            "<pre>\n```\n</pre>\n",
            "<?php\n```\n?>\n",
            "<!DOCTYPE\n```\n>\n",
            "<![CDATA[\n```\n]]>\n",
            "<div>\n```\n</div>\n",
            "<x-widget>\n```\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for raw_html in raw_html_blocks:
                with self.subTest(raw_html=raw_html):
                    template.write_text(raw_html, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

    def test_preserves_java_language_in_raw_html_code_blocks(self):
        java_blocks = [
            (
                '<pre><code class="language-java">\n'
                "assertThat(result).isEqualTo(expected);\n"
                "</code></pre>\n"
            ),
            (
                "<pre>\n"
                "<code class='example language-java'>\n"
                "assertThat(result).isEqualTo(expected);\n"
                "</code>\n"
                "</pre>\n"
            ),
            (
                '<pre><code title="1 > 0" class="language-java">\n'
                "assertThat(result).isEqualTo(expected);\n"
                "</code></pre>\n"
            ),
            (
                "<pre>\n"
                "<code\n"
                ' class="language-java">\n'
                "assertThat(result).isEqualTo(expected);\n"
                "</code>\n"
                "</pre>\n"
            ),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for java_block in java_blocks:
                with self.subTest(java_block=java_block):
                    template.write_text(java_block, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

    def test_raw_html_kotlin_code_still_uses_kotlin_assertion_rule(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                '<pre><code class="language-kotlin">\n'
                "assertThat(result).isEqualTo(expected)\n"
                "</code></pre>\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(2, findings[0].line)
            self.assertEqual(
                "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
                findings[0].message,
            )

    def test_raw_html_java_language_ends_with_code_element(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "<pre>\n"
                '<code class="language-java">\n'
                "assertThat(javaResult).isEqualTo(expected);\n"
                "</code>\n"
                "assertThat(kotlinResult).isEqualTo(expected)\n"
                "</pre>\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(5, findings[0].line)
            self.assertEqual(
                "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
                findings[0].message,
            )

    def test_raw_html_data_class_does_not_declare_java(self):
        sources = [
            (
                '<pre><code data-class="language-java">\n'
                "assertThat(result).isEqualTo(expected)\n"
                "</code></pre>\n",
                2,
            ),
            (
                "<pre>\n"
                '<!-- <code class="language-java"> -->\n'
                "assertThat(result).isEqualTo(expected)\n"
                "</pre>\n",
                3,
            ),
            (
                "<pre>\n"
                "<!--\n"
                '<code class="language-java">\n'
                "-->\n"
                "assertThat(result).isEqualTo(expected)\n"
                "</pre>\n",
                5,
            ),
            (
                "<pre>\n"
                '<code title=" class=\'language-java\' ">\n'
                "assertThat(result).isEqualTo(expected)\n"
                "</code>\n"
                "</pre>\n",
                3,
            ),
            (
                "<textarea>\n"
                '<code class="language-java">\n'
                "assertThat(result).isEqualTo(expected)\n"
                "</textarea>\n",
                3,
            ),
        ]
        sources.extend(
            (
                (
                    "<div>\n"
                    f"<{raw_text_tag}>\n"
                    'const template = \'<code class="language-java">\';\n'
                    "assertThat(result).isEqualTo(expected)\n"
                    f"</{raw_text_tag}>\n"
                ),
                4,
            )
            for raw_text_tag in ("script", "style", "textarea")
        )
        sources.append(
            (
                "<div>\n"
                "<script\n"
                ">\n"
                '<code class="language-java">\n'
                "assertThat(result).isEqualTo(expected)\n"
                "</script\n"
                ">\n",
                5,
            )
        )
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for source, expected_line in sources:
                with self.subTest(source=source):
                    template.write_text(source, encoding="utf-8")

                    findings = skill_lint.lint(root)

                    self.assertEqual(1, len(findings))
                    self.assertEqual(expected_line, findings[0].line)

    def test_raw_html_language_ends_with_inline_code_close(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                '<pre><code class="language-java">\n'
                "javaCall();\n"
                "</code> assertThat(result).isEqualTo(expected)\n"
                "</pre>\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(3, findings[0].line)

    def test_resumes_fence_detection_after_raw_html_block(self):
        raw_html_blocks = [
            "<!--\n```\n-->\n```\n",
            "<script>\n```\n</script>\n```\n",
            "<script>\n```\n</pre>\n```\n",
            "<pre>\n```\n</pre>\n```\n",
            "<div>\n```\n</div>\n\n```\n",
            "<x-widget data-id=\"1\">\n```\n\n```\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for raw_html in raw_html_blocks:
                with self.subTest(raw_html=raw_html):
                    template.write_text(raw_html, encoding="utf-8")
                    findings = skill_lint.lint(root)
                    self.assertEqual(1, len(findings))
                    self.assertEqual(
                        raw_html.count("\n"),
                        findings[0].line,
                    )
                    self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

    def test_type_7_html_block_does_not_interrupt_paragraph(self):
        paragraphs = [
            "Paragraph\n<x-widget>\n```\n",
            "- Paragraph\n  <x-widget>\n  ```\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for paragraph in paragraphs:
                with self.subTest(paragraph=paragraph):
                    template.write_text(paragraph, encoding="utf-8")
                    findings = skill_lint.lint(root)
                    self.assertEqual(1, len(findings))
                    self.assertEqual(3, findings[0].line)
                    self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

    def test_type_7_html_block_tracks_paragraph_container(self):
        raw_html_blocks = [
            "> Paragraph\n\n<x-widget>\n```\n\n```\n",
            "Paragraph\n> <x-widget>\n> ```\n>\n> ```\n",
            "# Heading\n<x-widget>\n```\n\n```\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for raw_html in raw_html_blocks:
                with self.subTest(raw_html=raw_html):
                    template.write_text(raw_html, encoding="utf-8")
                    findings = skill_lint.lint(root)
                    self.assertEqual(1, len(findings))
                    self.assertEqual(
                        raw_html.count("\n"),
                        findings[0].line,
                    )
                    self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

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

    def test_reports_fence_closed_outside_active_list_blockquote(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- item\n"
                "  > ```kotlin\n"
                "> ```\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual([2, 3], [finding.line for finding in findings])
            self.assertTrue(
                all(finding.message == "Unclosed Markdown code fence." for finding in findings)
            )

    def test_reports_alternating_fence_closed_outside_active_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- item\n"
                "  > - ~~~java\n"
                "  >   code\n"
                ">   ~~~\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual([2, 4], [finding.line for finding in findings])
            self.assertTrue(
                all(finding.message == "Unclosed Markdown code fence." for finding in findings)
            )

    def test_preserves_inner_list_container_after_paragraph(self):
        cases = [
            (
                "- > - Paragraph\n"
                "  >   ```kotlin\n"
                "  > ```\n",
                "- > - Paragraph\n"
                "  >   ```kotlin\n"
                "  >   ```\n",
            ),
            (
                "- - Paragraph\n"
                "    ```kotlin\n"
                "  ```\n",
                "- - Paragraph\n"
                "    ```kotlin\n"
                "    ```\n",
            ),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for invalid_close, valid_close in cases:
                with self.subTest(invalid_close=invalid_close):
                    template.write_text(invalid_close, encoding="utf-8")
                    findings = skill_lint.lint(root)

                    self.assertEqual([2, 3], [finding.line for finding in findings])
                    self.assertTrue(
                        all(
                            finding.message == "Unclosed Markdown code fence."
                            for finding in findings
                        )
                    )

                    template.write_text(valid_close, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

    def test_preserves_inner_list_container_for_html_after_paragraph(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- - Paragraph\n"
                "    <div>\n"
                "  ```\n"
                "\n"
                "  ```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_persists_recursive_list_container_across_non_paragraph_lines(self):
        cases = [
            (
                "- - # Heading\n"
                "    ```kotlin\n"
                "  ```\n",
                [2, 3],
                "- - # Heading\n"
                "    ```kotlin\n"
                "    ```\n",
            ),
            (
                "- > - # Heading\n"
                "  >   ```kotlin\n"
                "  > ```\n",
                [2, 3],
                "- > - # Heading\n"
                "  >   ```kotlin\n"
                "  >   ```\n",
            ),
            (
                "- - Paragraph\n"
                "\n"
                "    ```kotlin\n"
                "  ```\n",
                [3, 4],
                "- - Paragraph\n"
                "\n"
                "    ```kotlin\n"
                "    ```\n",
            ),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for source, expected_lines, valid_source in cases:
                with self.subTest(source=source):
                    template.write_text(source, encoding="utf-8")
                    findings = skill_lint.lint(root)

                    self.assertEqual(
                        expected_lines,
                        [finding.line for finding in findings],
                    )
                    self.assertTrue(
                        all(
                            finding.message == "Unclosed Markdown code fence."
                            for finding in findings
                        )
                    )

                    template.write_text(valid_source, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

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

    def test_allows_fence_in_alternating_nested_containers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- > - ~~~java\n"
                "  >   assertThat(result).isEqualTo(expected);\n"
                "  >   ~~~\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_allows_blank_line_in_alternating_nested_containers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- > - ~~~java\n"
                "  >   assertThat(first).isEqualTo(expected);\n"
                "  >\n"
                "  >   assertThat(second).isEqualTo(expected);\n"
                "  >   ~~~\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_preserves_lazy_blockquote_paragraph_continuation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "> Paragraph\n"
                "<x-widget>\n"
                "```\n"
                "\n"
                "```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_preserves_partial_lazy_blockquote_paragraph_prefix(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "> > Paragraph\n"
                "> <x-widget>\n"
                "> ```\n"
                ">\n"
                "> ```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_lazy_paragraph_does_not_skip_unmatched_outer_list_prefix(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- > Paragraph\n"
                "> <x-widget>\n"
                "> ```\n"
                ">\n"
                "> ```\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual([5], [finding.line for finding in findings])
            self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

    def test_preserves_paragraph_in_alternating_nested_containers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "- > - Paragraph\n"
                "  >   <x-widget>\n"
                "  >   ```\n"
                "  >\n"
                "  >   ```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_non_one_ordered_marker_does_not_interrupt_paragraph(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "Paragraph\n"
                "2. ~~~java\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_link_reference_definition_ends_paragraph_state(self):
        definitions = [
            "[label]: /url\n",
            "[label]:/url\n",
            "[label\\]]: /url\n",
            "[label]: foo<bar\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for definition in definitions:
                with self.subTest(definition=definition):
                    template.write_text(
                        definition + "2. ~~~java\n",
                        encoding="utf-8",
                    )

                    findings = skill_lint.lint(root)

                    self.assertEqual(1, len(findings))
                    self.assertEqual(2, findings[0].line)
                    self.assertEqual(
                        "Unclosed Markdown code fence.",
                        findings[0].message,
                    )

    def test_multiline_link_reference_definition_ends_paragraph_state(self):
        definitions = [
            "[label]:\n  /url\n",
            "[label]:\n    /url\n",
            "[label]:\n\t/url\n",
            "[label]: /url\n  \"title\"\n",
            "[label]:\n  /url\n  'title'\n",
            "> [label]:\n> /url\n",
            "[label]: /url \"first\nsecond\"\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for definition in definitions:
                with self.subTest(definition=definition):
                    template.write_text(
                        definition + "2. ~~~java\n",
                        encoding="utf-8",
                    )

                    findings = skill_lint.lint(root)

                    self.assertEqual(1, len(findings))
                    self.assertEqual(
                        definition.count("\n") + 1,
                        findings[0].line,
                    )
                    self.assertEqual(
                        "Unclosed Markdown code fence.",
                        findings[0].message,
                    )

    def test_link_reference_uses_active_and_lazy_containers(self):
        cases = [
            (
                "- - item\n"
                "\n"
                "    [label]:\n"
                "      /url\n"
                "    2. ~~~java\n",
                5,
            ),
            (
                '> [label]: /url "first\n'
                'outside"\n'
                "2. ~~~java\n",
                3,
            ),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for source, expected_line in cases:
                with self.subTest(source=source):
                    template.write_text(source, encoding="utf-8")

                    findings = skill_lint.lint(root)

                    self.assertEqual(1, len(findings))
                    self.assertEqual(expected_line, findings[0].line)
                    self.assertEqual(
                        "Unclosed Markdown code fence.",
                        findings[0].message,
                    )

    def test_invalid_link_reference_remains_paragraph_text(self):
        invalid_definitions = [
            "[label] /url\n",
            "[ ]: /url\n",
            "[label]: <foo\n",
            "[label]: /foo(bar\n",
            "[label]:\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for definition in invalid_definitions:
                with self.subTest(definition=definition):
                    template.write_text(
                        definition + "2. ~~~java\n",
                        encoding="utf-8",
                    )

                    self.assertEqual([], skill_lint.lint(root))

    def test_one_ordered_marker_can_interrupt_paragraph(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "Paragraph\n"
                "1. ~~~java\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual(1, len(findings))
            self.assertEqual(2, findings[0].line)
            self.assertEqual("Unclosed Markdown code fence.", findings[0].message)

    def test_indented_ordered_marker_preserves_paragraph_continuation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "Paragraph\n"
                "    1. text\n"
                "<x-widget>\n"
                "```\n"
                "\n"
                "```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_setext_underline_does_not_start_list_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "Paragraph\n"
                "- \n"
                "  ```java\n"
                "assertThat(result).isEqualTo(expected);\n"
                "```\n",
                encoding="utf-8",
            )

            self.assertEqual([], skill_lint.lint(root))

    def test_standalone_setext_like_text_starts_paragraph(self):
        sources = [
            "===\n<x-widget>\n```\n\n```\n",
            "--\n<x-widget>\n```\n\n```\n",
            "- ===\n  <x-widget>\n  ```\n\n  ```\n",
            "- item\n\n  --\n  <x-widget>\n  ```\n\n  ```\n",
            "> ===\n> <x-widget>\n> ```\n>\n> ```\n",
            "> --\n> <x-widget>\n> ```\n>\n> ```\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for source in sources:
                with self.subTest(source=source):
                    template.write_text(source, encoding="utf-8")
                    self.assertEqual([], skill_lint.lint(root))

    def test_empty_list_marker_starts_list_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            template.write_text(
                "-\n"
                "  ```kotlin\n"
                "```\n",
                encoding="utf-8",
            )

            findings = skill_lint.lint(root)

            self.assertEqual([2, 3], [finding.line for finding in findings])
            self.assertTrue(
                all(finding.message == "Unclosed Markdown code fence." for finding in findings)
            )

    def test_thematic_break_does_not_start_list_container(self):
        sources = [
            "Paragraph\n- - -\n    ```\n",
            "Paragraph\n* * *\n    ```\n",
            "- Paragraph\n  - - -\n      ```\n",
            "- Paragraph\n  * * *\n      ```\n",
            "> Paragraph\n> - - -\n>     ```\n",
            "> Paragraph\n> * * *\n>     ```\n",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            template = root / "skills" / "wow" / "references" / "template.md"
            template.parent.mkdir(parents=True)
            for source in sources:
                with self.subTest(source=source):
                    template.write_text(source, encoding="utf-8")
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
