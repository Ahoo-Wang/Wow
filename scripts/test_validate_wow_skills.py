from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))

import validate_wow_skills as validator  # noqa: E402


class ValidateWowSkillsTest(unittest.TestCase):
    def test_current_package_passes_without_external_validator(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT_DIR / "validate_wow_skills.py"),
                "--repo-root",
                str(REPO_ROOT),
                "--skip-quick-validator",
            ],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Validated 4 skills", result.stdout)

    def test_validator_has_no_site_package_dependency(self) -> None:
        result = subprocess.run(
            [
                sys.executable,
                "-S",
                str(SCRIPT_DIR / "validate_wow_skills.py"),
                "--repo-root",
                str(REPO_ROOT),
                "--skip-quick-validator",
            ],
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_activation_rejects_unknown_skill(self) -> None:
        validation = validator.Validation()
        cases = [
            {
                "__source__": "case:1",
                "schemaVersion": 1,
                "id": "unknown",
                "prompt": "Implement an aggregate.",
                "expectedSkills": ["unknown-skill"],
                "tags": [],
            }
        ]

        validator.validate_activation_cases(cases, {"wow-develop"}, validation)

        self.assertTrue(
            any("unknown expected skills" in error for error in validation.errors)
        )

    def test_behavior_rejects_invalid_regex(self) -> None:
        validation = validator.Validation()
        cases = [
            {
                "__source__": "case:1",
                "schemaVersion": 1,
                "id": "invalid-regex",
                "skill": "wow-debug",
                "prompt": "Diagnose the failure.",
                "assertions": [
                    {"type": "output.regex", "pattern": "[unterminated"}
                ],
            }
        ]

        validator.validate_behavior_cases(cases, {"wow-debug"}, validation)

        self.assertTrue(any("invalid regex" in error for error in validation.errors))

    def test_behavior_rejects_missing_fixture(self) -> None:
        validation = validator.Validation()
        cases = [
            {
                "__source__": "case:1",
                "schemaVersion": 1,
                "id": "missing-fixture",
                "skill": "wow-debug",
                "prompt": "Diagnose the failure.",
                "assertions": [
                    {"type": "workspace.clean", "value": True}
                ],
            }
        ]

        validator.validate_behavior_cases(cases, {"wow-debug"}, validation)

        self.assertTrue(any("fixture must be an object" in error for error in validation.errors))

    def test_resource_validation_rejects_cross_skill_link(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_dir = Path(temporary_directory) / "wow-develop"
            skill_dir.mkdir()
            (skill_dir / "SKILL.md").write_text(
                "# Test\n\n[other](../wow-debug/SKILL.md)\n",
                encoding="utf-8",
            )
            validation = validator.Validation()

            validator.validate_resource_links(skill_dir, validation)

            self.assertTrue(
                any("cross-skill link" in error for error in validation.errors)
            )

    def test_resource_validation_accepts_direct_markdown_link(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_dir = Path(temporary_directory) / "wow-develop"
            references_dir = skill_dir / "references"
            references_dir.mkdir(parents=True)
            (skill_dir / "SKILL.md").write_text(
                "# Test\n\n[Reference](references/example.md)\n",
                encoding="utf-8",
            )
            (references_dir / "example.md").write_text("# Example\n", encoding="utf-8")
            validation = validator.Validation()

            validator.validate_resource_links(skill_dir, validation)

            self.assertEqual([], validation.errors)


if __name__ == "__main__":
    unittest.main()
