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
        path.write_text(path.read_text(encoding="utf-8").replace("$wow-review", "$other"), encoding="utf-8")
        self.assert_error("default_prompt must reference $wow-review")

    def test_plugin_include_must_match_the_four_skill_directories(self) -> None:
        path = self.root / "skills" / "plugins.json"
        manifest = json.loads(path.read_text(encoding="utf-8"))
        manifest["plugins"][0]["skills"]["include"].remove("wow-debug")
        path.write_text(json.dumps(manifest), encoding="utf-8")
        self.assert_error("included, installed, and expected Skills must match")

    def test_resource_references_must_exist_and_stay_inside_the_skill(self) -> None:
        path = self.root / "skills" / "wow-develop" / "SKILL.md"
        original = path.read_text(encoding="utf-8")
        for reference, expected in (
            ("references/missing.md", "referenced resource does not exist"),
            ("references/../outside.md", "resource path escapes the Skill"),
            ("[outside](../outside.md)", "local link escapes the Skill"),
            ("[absolute](/absolute/path)", "local link escapes the Skill"),
            ("[file](file:///etc/passwd)", "local link scheme is not allowed"),
        ):
            with self.subTest(reference=reference):
                addition = reference if reference.startswith("[") else f"Load `{reference}`."
                path.write_text(original + f"\n{addition}\n", encoding="utf-8")
                self.assert_error(expected)
        path.write_text(original, encoding="utf-8")

    def test_eval_jsonl_rejects_invalid_json_duplicate_ids_and_unknown_skills(self) -> None:
        behavior = self.root / "skills" / "wow-debug" / "evals" / "behavior.jsonl"
        with self.subTest(boundary="invalid-json"):
            original = behavior.read_text(encoding="utf-8")
            behavior.write_text(original + "{\n", encoding="utf-8")
            self.assert_error("invalid JSON")
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
        record = {
            "id": "B99-escape",
            "skill": "wow-review",
            "prompt": "review a fixture",
            "fixture": "../outside.patch",
        }
        path.write_text(path.read_text(encoding="utf-8") + json.dumps(record) + "\n", encoding="utf-8")
        self.assert_error("fixture path escapes the eval directory")


if __name__ == "__main__":
    unittest.main()
