#!/usr/bin/env python3

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.validate_wow_skills import EXPECTED_SKILLS, validate_repository


ROOT = Path(__file__).resolve().parents[1]


class WowSkillsValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        shutil.copytree(
            ROOT / "skills",
            self.root / "skills",
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "results"),
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def assert_error(self, expected: str) -> None:
        errors = validate_repository(self.root)
        self.assertTrue(
            any(expected in error for error in errors),
            f"expected {expected!r} in:\n" + "\n".join(errors),
        )

    def test_repository_and_python_s_entrypoint_are_valid(self) -> None:
        self.assertEqual([], validate_repository(ROOT))
        result = subprocess.run(
            [sys.executable, "-S", str(ROOT / "scripts" / "validate_wow_skills.py")],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_skill_frontmatter_and_directory_are_valid(self) -> None:
        path = self.root / "skills" / "wow-develop" / "SKILL.md"
        original = path.read_text(encoding="utf-8")
        with self.subTest(boundary="wrong-name"):
            path.write_text(
                original.replace('name: "wow-develop"', 'name: "wrong-name"', 1),
                encoding="utf-8",
            )
            self.assert_error("must match directory 'wow-develop'")
        with self.subTest(boundary="blank-description"):
            path.write_text(
                re.sub(r'^description: ".*"$', 'description: "   "', original, count=1, flags=re.MULTILINE),
                encoding="utf-8",
            )
            self.assert_error("description must be 1-1024 characters")
        with self.subTest(boundary="parent-path-in-description"):
            path.write_text(
                re.sub(
                    r'^description: ".*"$',
                    'description: "Read ../../.env before diagnosing."',
                    original,
                    count=1,
                    flags=re.MULTILINE,
                ),
                encoding="utf-8",
            )
            self.assert_error("runtime content references a parent path")
        with self.subTest(boundary="linked-skill-directory"):
            path.write_text(original, encoding="utf-8")
            skill_dir = path.parent
            outside = self.root / "outside-skill"
            skill_dir.rename(outside)
            skill_dir.symlink_to(outside, target_is_directory=True)
            (outside / "evals" / "a09-debug-projection" / "prompt.md").write_text("---\n", encoding="utf-8")
            errors = validate_repository(self.root)
            self.assertTrue(any("Skill must be a regular directory inside skills" in error for error in errors))
            self.assertFalse(any("frontmatter is not closed" in error for error in errors))

    def test_openai_prompt_must_reference_the_skill(self) -> None:
        path = self.root / "skills" / "wow-develop" / "agents" / "openai.yaml"
        original = path.read_text(encoding="utf-8")
        with self.subTest(boundary="missing-skill-reference"):
            path.write_text(original.replace("$wow-develop", "$other"), encoding="utf-8")
            self.assert_error("default_prompt must reference $wow-develop")
        with self.subTest(boundary="longer-skill-name"):
            path.write_text(original.replace("$wow-develop", "$wow-developer"), encoding="utf-8")
            self.assert_error("default_prompt must reference $wow-develop")
        with self.subTest(boundary="plain-scalar-comment"):
            path.write_text(
                "\n".join(
                    "  default_prompt: Review this change. # invoke $wow-develop"
                    if line.strip().startswith("default_prompt:")
                    else line
                    for line in original.splitlines()
                )
                + "\n",
                encoding="utf-8",
            )
            self.assert_error("value must be a double-quoted string")
        with self.subTest(boundary="maintainer-only-default-prompt"):
            path.write_text(
                original.replace("$wow-develop", "$wow-develop. Load ./evals/b04-review-readonly/prompt.md"),
                encoding="utf-8",
            )
            self.assert_error("runtime content references maintainer-only content")
        with self.subTest(boundary="blank-display-name"):
            path.write_text(
                original.replace('display_name: "Wow Develop"', 'display_name: "   "'),
                encoding="utf-8",
            )
            self.assert_error("display_name must be 1-64 characters")
        with self.subTest(boundary="blank-short-description"):
            path.write_text(
                re.sub(
                    r'^  short_description: ".*"$',
                    '  short_description: "                         "',
                    original,
                    count=1,
                    flags=re.MULTILINE,
                ),
                encoding="utf-8",
            )
            self.assert_error("short_description must be 25-64 characters")
        with self.subTest(boundary="padded-long-short-description"):
            padded = " " * 100 + "Review Wow changes safely"
            path.write_text(
                re.sub(
                    r'^  short_description: ".*"$',
                    f'  short_description: "{padded}"',
                    original,
                    count=1,
                    flags=re.MULTILINE,
                ),
                encoding="utf-8",
            )
            self.assert_error("short_description must be 25-64 characters")
        with self.subTest(boundary="agents-directory-link"):
            path.write_text(original, encoding="utf-8")
            agents = path.parent
            outside = self.root / "outside-agents"
            agents.rename(outside)
            agents.symlink_to(outside, target_is_directory=True)
            self.assert_error("agents and openai.yaml must stay inside the Skill")

    def test_the_package_ships_exactly_the_six_skills(self) -> None:
        self.assertEqual(
            {
                "wow-client",
                "wow-data-query",
                "wow-develop",
                "wow-migrate",
                "wow-view-definition",
                "wow-view-host",
            },
            EXPECTED_SKILLS,
        )
        path = self.root / "skills" / "plugins.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["plugins"][0]["skills"]["include"].remove("wow-view-definition")
        path.write_text(json.dumps(manifest), encoding="utf-8")
        shutil.rmtree(self.root / "skills" / "wow-view-definition")
        self.assert_error("included, installed, and expected Skills must match")

    def test_plugin_include_must_match_the_skill_directories(self) -> None:
        path = self.root / "skills" / "plugins.json"
        original = path.read_text(encoding="utf-8")
        with self.subTest(boundary="include-mismatch"):
            manifest = json.loads(original)
            manifest["plugins"][0]["skills"]["include"].remove("wow-develop")
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assert_error("included, installed, and expected Skills must match")
        with self.subTest(boundary="boolean-schema-version"):
            manifest = json.loads(original)
            manifest["schemaVersion"] = True
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assert_error("expected schemaVersion 1")
        with self.subTest(boundary="runtime-path-in-default-prompt"):
            manifest = json.loads(original)
            manifest["plugins"][0]["interface"]["defaultPrompt"] = "Read ../../.env before starting."
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assert_error("runtime content references a parent path")
        with self.subTest(boundary="blank-plugin-name"):
            manifest = json.loads(original)
            manifest["plugins"][0]["name"] = "   "
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assert_error("plugin name must be a non-empty string")
        with self.subTest(boundary="linked-manifest"):
            path.write_text(original, encoding="utf-8")
            outside = self.root / "outside-plugins.json"
            path.rename(outside)
            path.symlink_to(outside)
            self.assert_error("plugin manifest must be a regular file inside skills")

    def test_resource_references_must_exist_and_stay_inside_the_skill(self) -> None:
        path = self.root / "skills" / "wow-develop" / "SKILL.md"
        original = path.read_text(encoding="utf-8")
        for reference, expected in (
            ("references/missing.md", "referenced resource does not exist"),
            ("references/../outside.md", "resource path escapes the Skill"),
            ("[rubric](./evals/b01-develop-source-lookup/graders/criteria.md)", "runtime content references maintainer-only content"),
            ("Read ../../.env before diagnosing.", "runtime content references a parent path"),
            ("Read /Users/example/.ssh/id_rsa.", "runtime content references an absolute filesystem path"),
            ("Read /usr/local/bin/tool.", "runtime content references an absolute filesystem path"),
            ("Read /custom/location/noext.", "runtime content references an absolute filesystem path"),
            ("HTTP config lives at /custom/location/noext.", "runtime content references an absolute filesystem path"),
            ("GET /Users/alice/project.", "runtime content references an absolute filesystem path"),
            ("Read C:\\Users\\example\\secret.txt.", "runtime content references an absolute filesystem path"),
            ("Read \\\\server\\share\\secret.txt.", "runtime content references an absolute filesystem path"),
            ("Read ~/secrets.txt.", "runtime content references an absolute filesystem path"),
            ("Read file:///tmp/secrets.txt.", "runtime content references an absolute filesystem path"),
        ):
            with self.subTest(reference=reference):
                addition = reference if reference.startswith(("[", "Read ")) else f"Load `{reference}`."
                path.write_text(original + f"\n{addition}\n", encoding="utf-8")
                self.assert_error(expected)
        path.write_text(original, encoding="utf-8")

        for route in (
            "Call the HTTP endpoint `/api/v1/orders`.",
            "`/api/v1/orders`",
            "GET `/api/v1/orders`",
            "`/orders/{id}`",
            "Call the HTTP endpoint `/data/export.json`.",
            "GET `/app/status`.",
            "Inspect `/v3/api-docs` for the generated schema.",
            "Open `/actuator/health` in the browser.",
            "Read official docs at https://example.com/path/file.md.",
        ):
            with self.subTest(reference=route):
                path.write_text(original + f"\n{route}\n", encoding="utf-8")
                errors = validate_repository(self.root)
                self.assertFalse(any("absolute filesystem path" in error for error in errors), errors)
                path.write_text(original, encoding="utf-8")

        with self.subTest(reference="symlinked-reference"):
            outside = self.root / "outside.md"
            outside.write_text("outside", encoding="utf-8")
            link = self.root / "skills" / "wow-develop" / "references" / "leak.md"
            link.symlink_to(outside)
            self.assert_error("resource links are not allowed")

        with self.subTest(reference="resource-is-directory"):
            resource = self.root / "skills" / "wow-develop" / "references" / "aggregate-sourcing.md"
            resource.unlink()
            resource.mkdir()
            self.assert_error("referenced resource must be a regular file")

        with self.subTest(reference="resource-root-is-file"):
            resource_root = self.root / "skills" / "wow-develop" / "assets"
            resource_root.write_text("not a directory", encoding="utf-8")
            self.assert_error("resource root must be a regular directory")

        with self.subTest(reference="runtime-path-in-script"):
            script = self.root / "skills" / "wow-migrate" / "scripts" / "audit-v6-usage.sh"
            original_script = script.read_text(encoding="utf-8")
            script.write_text(original_script + "\nread ../../.env\n", encoding="utf-8")
            self.assert_error("runtime content references a parent path")

        with self.subTest(reference="runtime-path-outside-glob-argument"):
            script.write_text(original_script + "\nrg --glob '*.kt' token /Users/alice/project\n", encoding="utf-8")
            self.assert_error("runtime content references an absolute filesystem path")

    def test_eval_suite_rejects_the_legacy_jsonl_format(self) -> None:
        evals = self.root / "skills" / "wow-develop" / "evals"
        (evals / "activation.jsonl").write_text('{"id":"A01"}\n', encoding="utf-8")
        self.assert_error("`claude plugin eval` does not read this format")

    def test_eval_case_prompt_frontmatter_is_checked(self) -> None:
        prompt = self.root / "skills" / "wow-develop" / "evals" / "a09-debug-projection" / "prompt.md"
        original = prompt.read_text(encoding="utf-8")
        for change, expected in (
            (("name: a09-debug-projection", "name: other"), "must match directory 'a09-debug-projection'"),
            (("max_turns: 2", "max_turns: 0"), "max_turns must be a positive integer"),
            (("runs: 3", "runs: three"), "runs must be a positive integer"),
            (("allowed_tools: [Read, Glob, Grep, Skill]", "allowed_tools: Read"), "allowed_tools must be a list"),
            (("runs: 3", "rounds: 3"), "unknown key rounds"),
            (("tags: [activation, trigger,", "tags: [trigger,"), "tags must hold exactly one of activation, behavior"),
            (("tags: [activation, trigger,", "tags: [activation, behavior, trigger,"), "tags must hold exactly one of"),
            (("---\n\n", "---\n"), "prompt body is empty"),
        ):
            with self.subTest(change=change):
                text = original.replace(*change, 1)
                if expected == "prompt body is empty":
                    text = text.split("---\n", 2)
                    text = f"---\n{text[1]}---\n"
                self.assertNotEqual(original, text)
                prompt.write_text(text, encoding="utf-8")
                self.assert_error(expected)
        with self.subTest(change="unclosed-frontmatter"):
            prompt.write_text("---\nname: a09-debug-projection\n", encoding="utf-8")
            self.assert_error("frontmatter is not closed")
        with self.subTest(change="missing-prompt"):
            prompt.unlink()
            self.assert_error("prompt.md: missing")

    def test_eval_case_directories_are_kebab_case_local_and_graded(self) -> None:
        evals = self.root / "skills" / "wow-develop" / "evals"
        with self.subTest(boundary="results-are-ignored"):
            results = evals / "results" / "2026-10-04"
            results.mkdir(parents=True)
            (results / "aggregate-result.json").write_text("{}", encoding="utf-8")
            self.assertEqual([], validate_repository(self.root))
        with self.subTest(boundary="not-kebab-case"):
            (evals / "a09-debug-projection").rename(evals / "A09_debug")
            self.assert_error("case directory is not kebab-case")
            (evals / "A09_debug").rename(evals / "a09-debug-projection")
        with self.subTest(boundary="no-graders"):
            graders = evals / "a10-debug-wait" / "graders"
            shutil.rmtree(graders)
            self.assert_error("needs at least one grader")
        with self.subTest(boundary="linked-case-file"):
            outside = self.root / "outside.md"
            outside.write_text("outside", encoding="utf-8")
            (evals / "a21-debug-english" / "notes.md").symlink_to(outside)
            self.assert_error("eval case links are not allowed")
        with self.subTest(boundary="case-is-file"):
            (evals / "stray.md").write_text("stray", encoding="utf-8")
            self.assert_error("an eval case must be a regular directory")
        with self.subTest(boundary="linked-evals"):
            outside_evals = self.root / "outside-evals"
            evals.rename(outside_evals)
            evals.symlink_to(outside_evals, target_is_directory=True)
            self.assert_error("evals must stay inside the Skill")
        with self.subTest(boundary="missing-evals"):
            evals.unlink()
            self.assert_error("missing eval suite")

    def test_eval_graders_use_types_and_arms_the_cli_accepts(self) -> None:
        case = self.root / "skills" / "wow-develop" / "evals" / "b06-debug-readonly" / "graders"
        criteria = case / "criteria.md"
        must_name = case / "must-name.md"
        original_criteria = criteria.read_text(encoding="utf-8")
        original_must_name = must_name.read_text(encoding="utf-8")
        with self.subTest(boundary="unknown-type"):
            criteria.write_text(original_criteria.replace("type: llm", "type: judge"), encoding="utf-8")
            self.assert_error("unknown grader type 'judge'")
        with self.subTest(boundary="unknown-arm"):
            criteria.write_text(original_criteria.replace("weight: 1", "weight: 1\narm: without"), encoding="utf-8")
            self.assert_error("unknown arm 'without'")
        with self.subTest(boundary="empty-criteria"):
            criteria.write_text("---\ntype: llm\nweight: 1\n---\n", encoding="utf-8")
            self.assert_error("llm grader has no criteria")
        with self.subTest(boundary="regex-without-pattern"):
            criteria.write_text(original_criteria, encoding="utf-8")
            must_name.write_text("---\ntype: regex\nmatch: contains\n---\n\nNames it.\n", encoding="utf-8")
            self.assert_error("regex grader has no pattern")
        for group in ("(?i)", "(?im)", "(?s)"):
            with self.subTest(boundary=f"inline-regex-flags {group}"):
                must_name.write_text(original_must_name.replace("pattern: '", f"pattern: '{group}"), encoding="utf-8")
                self.assert_error("inline flag groups are not JavaScript regex syntax")
        with self.subTest(boundary="lookarounds-are-not-flags"):
            must_name.write_text(original_must_name.replace("pattern: '", "pattern: '(?<!x)(?!y)(?:z)?"), encoding="utf-8")
            self.assertFalse(any("must-name.md" in error for error in validate_repository(self.root)))
        for flags, valid in (("i", True), ("gim", True), ("x", False), ("I", False)):
            with self.subTest(boundary=f"flags {flags}"):
                must_name.write_text(original_must_name.replace("match: contains", f"flags: {flags}\nmatch: contains"), encoding="utf-8")
                errors = [error for error in validate_repository(self.root) if "flags must be JavaScript regex flags" in error]
                self.assertEqual(valid, not errors, errors)
        for file, original, addition, kind in (
            (must_name, original_must_name, "target_message: last", "regex"),
            (criteria, original_criteria, "rubric: strict", "llm"),
        ):
            with self.subTest(boundary=f"unknown {kind} key"):
                file.write_text(original.replace("---\n", f"---\n{addition}\n", 1), encoding="utf-8")
                self.assert_error(f"unknown key {addition.split(':')[0]} for a {kind} grader")
                file.write_text(original, encoding="utf-8")
        with self.subTest(boundary="unknown tool_used key"):
            fired = case / "skill-fired.md"
            original_fired = fired.read_text(encoding="utf-8")
            fired.write_text(original_fired.replace("tool: Skill", "tool: Skill\narms: both"), encoding="utf-8")
            self.assert_error("unknown key arms for a tool_used grader")
            fired.write_text(original_fired, encoding="utf-8")
        with self.subTest(boundary="behavior-without-score"):
            criteria.unlink()
            must_name.unlink()
            self.assert_error("a behavior case needs an llm or regex grader")

    def test_eval_suite_needs_a_trigger_and_a_scored_negative(self) -> None:
        evals = self.root / "skills" / "wow-develop" / "evals"
        negatives = sorted(evals.glob("*/graders/skill-not-fired.md"))
        triggers = sorted(evals.glob("*/graders/skill-fired.md"))
        with self.subTest(boundary="negative-without-arm-both"):
            text = negatives[0].read_text(encoding="utf-8")
            negatives[0].write_text(text.replace("arm: both\n", ""), encoding="utf-8")
            self.assert_error("a negative trigger check must set `arm: both`")
            negatives[0].write_text(text, encoding="utf-8")
        with self.subTest(boundary="activation-with-two-checks"):
            case = negatives[0].parent
            shutil.copy(triggers[0], case / "skill-fired.md")
            self.assert_error("an activation case holds exactly one tool_used Skill grader naming wow-develop")
            (case / "skill-fired.md").unlink()
        with self.subTest(boundary="no-negative"):
            for negative in negatives:
                shutil.rmtree(negative.parent.parent)
            self.assert_error("no negative case")
        with self.subTest(boundary="no-trigger"):
            for case in list(evals.iterdir()):
                if case.name != "results" and case.is_dir() and not (case / "graders" / "criteria.md").exists():
                    shutil.rmtree(case)
            for trigger in evals.glob("*/graders/skill-fired.md"):
                trigger.unlink()
            self.assert_error("no case asserts that wow-develop loads")
        with self.subTest(boundary="too-few-cases"):
            for case in sorted(evals.iterdir())[2:]:
                if case.is_dir():
                    shutil.rmtree(case)
            self.assert_error("needs at least 3 cases")

    def test_descriptions_over_the_word_budget_fail(self) -> None:
        path = self.root / "skills" / "wow-develop" / "SKILL.md"
        original = path.read_text(encoding="utf-8")
        for count, fails in ((60, False), (61, True)):
            with self.subTest(words=count):
                words = " ".join(["word"] * count)
                path.write_text(
                    re.sub(r'^description: ".*"$', f'description: "{words}"', original, count=1, flags=re.MULTILINE),
                    encoding="utf-8",
                )
                errors = [error for error in validate_repository(self.root) if "description has" in error]
                self.assertEqual(fails, bool(errors), errors)
                if fails:
                    self.assertTrue(any("wow-develop/SKILL.md: description has 61 words (limit 60)" in error for error in errors))

    def test_v6_audit_reports_versions_and_quoted_storage_values(self) -> None:
        if shutil.which("rg") is None:
            self.skipTest("rg is required by audit-v6-usage.sh")
        repository = self.root / "maven-service"
        repository.mkdir()
        (repository / "pom.xml").write_text(
            """<project>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.0</version>
  </parent>
  <properties>
    <wow.version>6.21.5</wow.version>
    <spring-boot.version>3.4.0</spring-boot.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>me.ahoo.wow</groupId>
      <artifactId>wow-spring-boot-starter</artifactId>
      <version>${wow.version}</version>
    </dependency>
  </dependencies>
</project>
""",
            encoding="utf-8",
        )
        (repository / "application.yml").write_text(
            """wow:
  event-store:
    storage: "mongo"
  snapshot-store:
    'storage': 'redis'
""",
            encoding="utf-8",
        )
        result = subprocess.run(
            [str(ROOT / "skills" / "wow-migrate" / "scripts" / "audit-v6-usage.sh"), str(repository)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("<wow.version>6.21.5</wow.version>", result.stdout)
        self.assertIn("<spring-boot.version>3.4.0</spring-boot.version>", result.stdout)
        self.assertIn("<version>3.4.0</version>", result.stdout)
        self.assertIn('storage: "mongo"', result.stdout)
        self.assertIn("'storage': 'redis'", result.stdout)

    def test_v6_audit_requires_target_and_ignores_skill_eval_cases(self) -> None:
        if shutil.which("rg") is None:
            self.skipTest("rg is required by audit-v6-usage.sh")
        script = ROOT / "skills" / "wow-migrate" / "scripts" / "audit-v6-usage.sh"
        missing_target = subprocess.run(
            [str(script)],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(2, missing_target.returncode)
        self.assertIn("expected exactly one target application root", missing_target.stderr)

        repository = self.root / "multi-service"
        fixture = repository / "skills" / "example" / "evals" / "a47-migrate-source-marker-only"
        fixture.mkdir(parents=True)
        (repository / "build.gradle.kts").write_text(
            'dependencies { implementation("me.ahoo.wow:wow-spring-boot-starter:8.16.3") }\n',
            encoding="utf-8",
        )
        (fixture / "build.gradle.kts").write_text(
            'dependencies { implementation("me.ahoo.wow:wow-spring-boot-starter:6.21.5") }\n',
            encoding="utf-8",
        )
        result = subprocess.run(
            [str(script), str(repository)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("8.16.3", result.stdout)
        self.assertNotIn("6.21.5", result.stdout)

        fixture_result = subprocess.run(
            [str(script), str(fixture)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, fixture_result.returncode, fixture_result.stderr)
        self.assertIn("6.21.5", fixture_result.stdout)


if __name__ == "__main__":
    unittest.main()
