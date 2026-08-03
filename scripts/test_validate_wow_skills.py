#!/usr/bin/env python3

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.validate_wow_skills import validate_repository


ROOT = Path(__file__).resolve().parents[1]


class WowSkillsValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        shutil.copytree(
            ROOT / "skills",
            self.root / "skills",
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc"),
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

    def test_frontmatter_name_must_match_directory(self) -> None:
        path = self.root / "skills" / "wow-debug" / "SKILL.md"
        path.write_text(
            path.read_text(encoding="utf-8").replace('name: "wow-debug"', 'name: "wrong-name"', 1),
            encoding="utf-8",
        )
        self.assert_error("must match directory 'wow-debug'")

    def test_openai_prompt_must_reference_the_skill(self) -> None:
        path = self.root / "skills" / "wow-review" / "agents" / "openai.yaml"
        original = path.read_text(encoding="utf-8")
        with self.subTest(boundary="missing-skill-reference"):
            path.write_text(original.replace("$wow-review", "$other"), encoding="utf-8")
            self.assert_error("default_prompt must reference $wow-review")
        with self.subTest(boundary="longer-skill-name"):
            path.write_text(original.replace("$wow-review", "$wow-reviewer"), encoding="utf-8")
            self.assert_error("default_prompt must reference $wow-review")
        with self.subTest(boundary="plain-scalar-comment"):
            path.write_text(
                "\n".join(
                    "  default_prompt: Review this change. # invoke $wow-review"
                    if line.strip().startswith("default_prompt:")
                    else line
                    for line in original.splitlines()
                )
                + "\n",
                encoding="utf-8",
            )
            self.assert_error("value must be a double-quoted string")
        with self.subTest(boundary="agents-directory-link"):
            path.write_text(original, encoding="utf-8")
            agents = path.parent
            outside = self.root / "outside-agents"
            agents.rename(outside)
            agents.symlink_to(outside, target_is_directory=True)
            self.assert_error("agents and openai.yaml must stay inside the Skill")

    def test_plugin_include_must_match_the_four_skill_directories(self) -> None:
        path = self.root / "skills" / "plugins.json"
        original = path.read_text(encoding="utf-8")
        with self.subTest(boundary="include-mismatch"):
            manifest = json.loads(original)
            manifest["plugins"][0]["skills"]["include"].remove("wow-debug")
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assert_error("included, installed, and expected Skills must match")
        with self.subTest(boundary="boolean-schema-version"):
            manifest = json.loads(original)
            manifest["schemaVersion"] = True
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assert_error("expected schemaVersion 1")
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
            ("[outside](../outside.md)", "local link escapes the Skill"),
            ("[absolute](/absolute/path)", "local link escapes the Skill"),
            ("[file](file:///etc/passwd)", "local link scheme is not allowed"),
            ("[outside][escape]\n\n[escape]: ../outside.md", "local link escapes the Skill"),
            (
                "[outside](<references/pipeline-map.md /../../../outside.md>)",
                "local link escapes the Skill",
            ),
            ('<a href="../outside.md">outside</a>', "raw HTML resource links are not allowed"),
            ('<script src="../outside.js"></script>', "raw HTML resource links are not allowed"),
        ):
            with self.subTest(reference=reference):
                addition = reference if reference.startswith(("[", "<")) else f"Load `{reference}`."
                path.write_text(original + f"\n{addition}\n", encoding="utf-8")
                self.assert_error(expected)
        path.write_text(original, encoding="utf-8")

        with self.subTest(reference="symlinked-reference"):
            outside = self.root / "outside.md"
            outside.write_text("[outside](../secret.md)", encoding="utf-8")
            link = self.root / "skills" / "wow-develop" / "references" / "leak.md"
            link.symlink_to(outside)
            errors = validate_repository(self.root)
            self.assertTrue(any("resource links are not allowed" in error for error in errors))
            self.assertFalse(any("local link" in error for error in errors))

        with self.subTest(reference="asset-local-link"):
            asset = self.root / "skills" / "wow-develop" / "assets" / "design-report.md"
            asset_original = asset.read_text(encoding="utf-8")
            asset.write_text(asset_original + "\n[outside](../../outside.md)\n", encoding="utf-8")
            self.assert_error("local link escapes the Skill")
            asset.write_text(asset_original, encoding="utf-8")

        with self.subTest(reference="resource-is-directory"):
            resource = self.root / "skills" / "wow-develop" / "assets" / "behavior-scenarios.md"
            resource.unlink()
            resource.mkdir()
            self.assert_error("referenced resource must be a regular file")

        with self.subTest(reference="resource-root-is-file"):
            resource_root = self.root / "skills" / "wow-debug" / "assets"
            resource_root.write_text("not a directory", encoding="utf-8")
            self.assert_error("resource root must be a regular directory")

    def test_eval_jsonl_rejects_invalid_json_duplicate_ids_and_unknown_skills(self) -> None:
        behavior = self.root / "skills" / "wow-debug" / "evals" / "behavior.jsonl"
        original = behavior.read_text(encoding="utf-8")
        with self.subTest(boundary="invalid-json"):
            behavior.write_text(original + "{\n", encoding="utf-8")
            self.assert_error("invalid JSON")
            behavior.write_text(original, encoding="utf-8")

        with self.subTest(boundary="duplicate-json-key"):
            duplicate = (
                '{"id":"B99-first","id":"B99-second","skill":"wow-debug",'
                '"prompt":"forward eval","expectedBehavior":["report evidence"]}'
            )
            behavior.write_text(original + duplicate + "\n", encoding="utf-8")
            self.assert_error("duplicate key 'id'")
            behavior.write_text(original, encoding="utf-8")

        with self.subTest(boundary="non-json-number"):
            invalid_number = (
                '{"id":"B99-nan","skill":"wow-debug","prompt":"forward eval",'
                '"expectedBehavior":["report evidence"],"extra":NaN}'
            )
            behavior.write_text(original + invalid_number + "\n", encoding="utf-8")
            self.assert_error("invalid constant NaN")
            behavior.write_text(original, encoding="utf-8")

        with self.subTest(boundary="duplicate-and-unknown"):
            record = {
                "id": "A01-develop-aggregate",
                "skill": "wow-unknown",
                "prompt": "forward eval",
            }
            behavior.write_text(original + json.dumps(record) + "\n", encoding="utf-8")
            errors = validate_repository(self.root)
            self.assertTrue(any("duplicate id" in error for error in errors))
            self.assertTrue(any("unknown Skill" in error for error in errors))
            self.assertTrue(any("expectedBehavior" in error for error in errors))

        with self.subTest(boundary="unhashable-skill"):
            record = {
                "id": "B99-invalid-skill",
                "skill": [],
                "prompt": "forward eval",
                "expectedBehavior": ["report evidence"],
            }
            behavior.write_text(original + json.dumps(record) + "\n", encoding="utf-8")
            self.assert_error("behavior case references unknown Skill []")

        with self.subTest(boundary="multiple-primary-skills"):
            activation = self.root / "skills" / "wow-debug" / "evals" / "activation.jsonl"
            records = [json.loads(line) for line in activation.read_text(encoding="utf-8").splitlines()]
            records[0]["expectedSkills"] = ["wow-debug", "wow-develop"]
            activation.write_text(
                "\n".join(json.dumps(record, ensure_ascii=False) for record in records) + "\n",
                encoding="utf-8",
            )
            self.assert_error("zero or one known Primary Skill")

    def test_eval_fixture_must_exist_inside_its_eval_directory(self) -> None:
        path = self.root / "skills" / "wow-review" / "evals" / "behavior.jsonl"
        original = path.read_text(encoding="utf-8")
        record = {
            "id": "B99-escape",
            "skill": "wow-review",
            "prompt": "review a fixture",
            "fixture": "../outside.patch",
        }
        with self.subTest(boundary="escape"):
            path.write_text(original + json.dumps(record) + "\n", encoding="utf-8")
            self.assert_error("fixture path must stay under evals/fixtures")

        with self.subTest(boundary="hidden-eval-data"):
            record["fixture"] = "behavior.jsonl"
            record["expectedBehavior"] = ["review the fixture"]
            path.write_text(original + json.dumps(record) + "\n", encoding="utf-8")
            self.assert_error("fixture path must stay under evals/fixtures")

        with self.subTest(boundary="nested-symlink"):
            path.write_text(original, encoding="utf-8")
            outside = self.root / "outside.txt"
            outside.write_text("outside", encoding="utf-8")
            link = self.root / "skills" / "wow-migrate" / "evals" / "fixtures" / "v6-service" / "leak"
            link.symlink_to(outside)
            self.assert_error("fixture contains a link")

        with self.subTest(boundary="symlinked-path-component"):
            fixtures = self.root / "skills" / "wow-review" / "evals" / "fixtures"
            alias = fixtures / "alias"
            alias.symlink_to(".", target_is_directory=True)
            record["id"] = "B99-linked-path"
            record["fixture"] = "fixtures/alias/B05.patch"
            path.write_text(original + json.dumps(record) + "\n", encoding="utf-8")
            self.assert_error("fixture path must not contain links")
            alias.unlink()
            path.write_text(original, encoding="utf-8")

        with self.subTest(boundary="symlinked-path-into-fixtures"):
            evals = self.root / "skills" / "wow-review" / "evals"
            alias = evals / "alias"
            alias.symlink_to("fixtures", target_is_directory=True)
            record["id"] = "B99-linked-root"
            record["fixture"] = "alias/B05.patch"
            path.write_text(original + json.dumps(record) + "\n", encoding="utf-8")
            self.assert_error("fixture path must stay under evals/fixtures")
            alias.unlink()
            path.write_text(original, encoding="utf-8")

        with self.subTest(boundary="fixtures-directory-link"):
            fixtures = self.root / "skills" / "wow-review" / "evals" / "fixtures"
            outside_fixtures = self.root / "outside-fixtures"
            fixtures.rename(outside_fixtures)
            fixtures.symlink_to(outside_fixtures, target_is_directory=True)
            self.assert_error("evals/fixtures must stay inside evals")

    def test_eval_contract_files_are_local_and_non_empty(self) -> None:
        with self.subTest(boundary="linked-file"):
            path = self.root / "skills" / "wow-review" / "evals" / "behavior.jsonl"
            outside = self.root / "outside-behavior.jsonl"
            path.rename(outside)
            path.symlink_to(outside)
            self.assert_error("eval data files must stay inside evals")

        with self.subTest(boundary="linked-directory"):
            evals = self.root / "skills" / "wow-debug" / "evals"
            outside_evals = self.root / "outside-evals"
            evals.rename(outside_evals)
            evals.symlink_to(outside_evals, target_is_directory=True)
            self.assert_error("evals must stay inside the Skill")

        with self.subTest(boundary="empty-data"):
            path = self.root / "skills" / "wow-develop" / "evals" / "activation.jsonl"
            path.write_text("\n", encoding="utf-8")
            self.assert_error("eval data must contain at least one valid record")

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


if __name__ == "__main__":
    unittest.main()
