from __future__ import annotations

import contextlib
import io
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
os.sys.path.insert(0, str(SCRIPT_DIR))

import validate_wow_skills as validator  # noqa: E402


class ValidateWowSkillsTest(unittest.TestCase):
    def test_current_package_passes_without_external_validator(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        isolated = {
            "HOME": "/tmp/wow-skill-validator-empty-home",
            "CODEX_HOME": "/tmp/wow-skill-validator-empty-codex",
            "SKILL_VALIDATOR": "/tmp/wow-skill-validator-missing",
        }

        with mock.patch.dict(os.environ, isolated, clear=False):
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = validator.main(["--repo-root", str(REPO_ROOT)])

        self.assertEqual(0, result, stdout.getvalue() + stderr.getvalue())
        self.assertIn("Validated 4 skills", stdout.getvalue())

    def test_validator_cli_runs_without_site_packages(self) -> None:
        environment = dict(os.environ)
        environment.update(
            {
                "HOME": "/tmp/wow-skill-validator-empty-home",
                "CODEX_HOME": "/tmp/wow-skill-validator-empty-codex",
                "SKILL_VALIDATOR": "/tmp/wow-skill-validator-missing",
                "PYTHONDONTWRITEBYTECODE": "1",
            }
        )

        result = subprocess.run(
            [
                os.sys.executable,
                "-S",
                str(SCRIPT_DIR / "validate_wow_skills.py"),
                "--repo-root",
                str(REPO_ROOT),
            ],
            capture_output=True,
            text=True,
            check=False,
            env=environment,
            timeout=30,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Validated 4 skills", result.stdout)

    def test_validator_module_cli_runs_without_site_packages(self) -> None:
        environment = dict(os.environ)
        environment["PYTHONDONTWRITEBYTECODE"] = "1"

        result = subprocess.run(
            [
                os.sys.executable,
                "-S",
                "-m",
                "scripts.validate_wow_skills",
                "--repo-root",
                str(REPO_ROOT),
            ],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
            env=environment,
            timeout=30,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Validated 4 skills", result.stdout)

    def test_repository_validation_rejects_duplicate_ids_across_suites(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo_root = Path(temporary_directory) / "repo"
            shutil.copytree(REPO_ROOT / "skills", repo_root / "skills")
            schema_source = REPO_ROOT / validator.TRACE_SCHEMA_RELATIVE_PATH
            schema_target = repo_root / validator.TRACE_SCHEMA_RELATIVE_PATH
            schema_target.parent.mkdir(parents=True)
            shutil.copy2(schema_source, schema_target)
            activation_path = (
                repo_root / "skills/wow-develop/evals/activation.jsonl"
            )
            activation_path.write_text(
                activation_path.read_text(encoding="utf-8").replace(
                    '"id":"A01-develop-aggregate"',
                    '"id":"B01-develop-source-lookup"',
                    1,
                ),
                encoding="utf-8",
            )
            migrate_skill = repo_root / "skills/wow-migrate/SKILL.md"
            migrate_skill.write_text(
                migrate_skill.read_text(encoding="utf-8").replace(
                    'description: "', 'description: "<invalid> ', 1
                ),
                encoding="utf-8",
            )
            validation = validator.Validation()

            validator.validate_repository(repo_root, validation)

            package_error = next(
                index
                for index, error in enumerate(validation.errors)
                if "angle brackets" in error
            )
            duplicate_error = next(
                index
                for index, error in enumerate(validation.errors)
                if "duplicate eval id" in error
            )
            self.assertLess(package_error, duplicate_error, validation.errors)

    def test_skill_metadata_rejects_non_standard_name_and_description(self) -> None:
        validation = validator.Validation()

        validator.validate_skill_metadata(
            {"name": "Bad--Name", "description": "contains <markup>"},
            "Bad--Name",
            Path("SKILL.md"),
            validation,
        )

        self.assertTrue(any("hyphen-case" in error for error in validation.errors))
        self.assertTrue(any("hyphen placement" in error for error in validation.errors))
        self.assertTrue(any("angle brackets" in error for error in validation.errors))

    def test_frontmatter_requires_json_quoted_string_scalars(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_file = Path(temporary_directory) / "SKILL.md"
            for plain_scalar in ("false", "null", "[a,b]", "{k:v}"):
                with self.subTest(plain_scalar=plain_scalar):
                    skill_file.write_text(
                        "---\n"
                        'name: "wow-test"\n'
                        f"description: {plain_scalar}\n"
                        "---\n\n# Test\n",
                        encoding="utf-8",
                    )
                    validation = validator.Validation()

                    metadata = validator.parse_frontmatter(skill_file, validation)

                    self.assertIsNone(metadata)
                    self.assertTrue(
                        any("double-quoted" in error for error in validation.errors)
                    )

            skill_file.write_text(
                "---\n"
                'name: "wow-test"\n'
                'description: "false"\n'
                "---\n\n# Test\n",
                encoding="utf-8",
            )
            validation = validator.Validation()

            self.assertEqual(
                {"name": "wow-test", "description": "false"},
                validator.parse_frontmatter(skill_file, validation),
            )
            self.assertEqual([], validation.errors)

    def test_frontmatter_delimiters_must_be_standalone_lines(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_file = Path(temporary_directory) / "SKILL.md"
            skill_file.write_text(
                "---\n"
                'name: "wow-test"\n'
                'description: "Test"\n'
                "---not-a-delimiter\n\n# Test\n",
                encoding="utf-8",
            )
            validation = validator.Validation()

            self.assertIsNone(validator.parse_frontmatter(skill_file, validation))
            self.assertTrue(
                any("standalone closing" in error for error in validation.errors)
            )

    def test_invalid_utf8_text_inputs_report_validation_errors(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            for label, relative_path, validate in (
                (
                    "SKILL",
                    Path("SKILL.md"),
                    lambda path, result: validator.parse_frontmatter(path, result),
                ),
                (
                    "JSONL",
                    Path("activation.jsonl"),
                    lambda path, result: validator.read_jsonl(path, result),
                ),
            ):
                with self.subTest(label=label):
                    path = root / relative_path
                    path.write_bytes(b"\xff")
                    validation = validator.Validation()

                    validate(path, validation)

                    self.assertTrue(validation.errors)

            schema_path = root / validator.TRACE_SCHEMA_RELATIVE_PATH
            schema_path.parent.mkdir(parents=True)
            schema_path.write_bytes(b"\xff")
            validation = validator.Validation()

            validator.validate_trace_schema(root, validation)

            self.assertTrue(
                any("invalid JSON" in error for error in validation.errors),
                validation.errors,
            )

    def test_trace_schema_rejects_missing_critical_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo_root = Path(temporary_directory)
            schema_path = repo_root / validator.TRACE_SCHEMA_RELATIVE_PATH
            schema_path.parent.mkdir(parents=True)
            schema_path.write_text("{}\n", encoding="utf-8")
            validation = validator.Validation()

            validator.validate_trace_schema(repo_root, validation)

            self.assertTrue(validation.errors)
            self.assertTrue(
                any("exact root fields" in error for error in validation.errors),
                validation.errors,
            )

    def test_trace_schema_rejects_malformed_json(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo_root = Path(temporary_directory)
            schema_path = repo_root / validator.TRACE_SCHEMA_RELATIVE_PATH
            schema_path.parent.mkdir(parents=True)
            schema_path.write_text("{not-json}\n", encoding="utf-8")
            validation = validator.Validation()

            validator.validate_trace_schema(repo_root, validation)

            self.assertTrue(
                any("invalid JSON" in error for error in validation.errors),
                validation.errors,
            )

    def test_activation_rejects_unknown_skill(self) -> None:
        validation = validator.Validation()
        case = {
            "__source__": "case:1",
            "schemaVersion": 1,
            "id": "unknown",
            "prompt": "Implement an aggregate.",
            "expectedSkills": ["unknown-skill"],
            "tags": [],
        }

        validator.validate_activation_case(case, {"wow-develop"}, validation)

        self.assertTrue(
            any("unknown expected skills" in error for error in validation.errors)
        )

    def test_activation_rejects_non_ascii_prompt_tagged_as_english(self) -> None:
        validation = validator.Validation()
        case = {
            "__source__": "case:1",
            "schemaVersion": 1,
            "id": "language-mismatch",
            "prompt": "Explain this aggregate，不要修改。",
            "expectedSkills": ["wow-develop"],
            "tags": ["language-en"],
        }

        validator.validate_activation_case(case, {"wow-develop"}, validation)

        self.assertTrue(
            any("language-en" in error for error in validation.errors)
        )

    def test_assertion_rejects_invalid_regex(self) -> None:
        validation = validator.Validation()

        validator.validate_assertion(
            {"type": "output.regex", "pattern": "[unterminated"},
            "case:1",
            validation,
        )

        self.assertTrue(any("invalid regex" in error for error in validation.errors))

    def test_behavior_rejects_absolute_fixture_paths(self) -> None:
        validation = validator.Validation()
        case = self.copied_read_only_case("/etc", "/etc/hosts")

        validator.validate_behavior_case(case, {"wow-debug"}, validation)

        self.assertTrue(any("contained relative path" in error for error in validation.errors))

    def test_behavior_rejects_traversal_fixture_paths(self) -> None:
        validation = validator.Validation()
        case = self.copied_read_only_case("../../outside", "../setup.patch")

        validator.validate_behavior_case(case, {"wow-debug"}, validation)

        self.assertTrue(any("contained relative path" in error for error in validation.errors))
        self.assertTrue(any("copied-directory" in error for error in validation.errors))

    def test_copied_fixture_rejects_nested_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            evals_dir = Path(temporary_directory) / "evals"
            repository = evals_dir / "fixtures/repository"
            repository.mkdir(parents=True)
            (repository / "outside").symlink_to("/etc")
            case = self.copied_read_only_case("repository", "none", evals_dir)
            validation = validator.Validation()

            validator.validate_behavior_case(case, {"wow-debug"}, validation)

            self.assertTrue(any("symlink" in error for error in validation.errors))

    def test_copied_setup_must_be_none(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            evals_dir = Path(temporary_directory) / "evals"
            fixtures = evals_dir / "fixtures"
            fixtures.mkdir(parents=True)
            (fixtures / "repository").mkdir()
            (fixtures / "setup.txt").write_text("not a patch\n", encoding="utf-8")
            case = self.copied_read_only_case("repository", "setup.txt", evals_dir)
            validation = validator.Validation()

            validator.validate_behavior_case(case, {"wow-debug"}, validation)

            self.assertTrue(any("copied-directory" in error for error in validation.errors))

    def test_isolated_setup_must_be_a_patch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            evals_dir = Path(temporary_directory) / "evals"
            fixtures = evals_dir / "fixtures"
            fixtures.mkdir(parents=True)
            (fixtures / "setup.txt").write_text("not a patch\n", encoding="utf-8")
            case = self.isolated_case(mode="read-only")
            case["__source__"] = f"{evals_dir / 'behavior.jsonl'}:1"
            case["fixture"]["setup"] = "setup.txt"
            validation = validator.Validation()

            validator.validate_behavior_case(case, {"wow-debug"}, validation)

            self.assertTrue(any(".patch" in error for error in validation.errors))

    def test_resource_validation_rejects_absolute_and_parent_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_dir = Path(temporary_directory) / "wow-develop"
            skill_dir.mkdir()
            (skill_dir / "SKILL.md").write_text(
                "# Test\n\n[host](/etc/hosts)\n[other](../other/SKILL.md)\n",
                encoding="utf-8",
            )
            validation = validator.Validation()

            validator.validate_resource_links(skill_dir, validation)

            self.assertEqual(2, len(validation.errors))
            self.assertTrue(all("escapes" in error for error in validation.errors))

    def test_resource_validation_accepts_direct_markdown_link(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_dir = Path(temporary_directory) / "wow-develop"
            references_dir = skill_dir / "references"
            references_dir.mkdir(parents=True)
            (skill_dir / "SKILL.md").write_text(
                "# Test\n\n[Reference](references/example.md)\n",
                encoding="utf-8",
            )
            (references_dir / "example.md").write_text(
                "# Example\n", encoding="utf-8"
            )
            validation = validator.Validation()

            validator.validate_resource_links(skill_dir, validation)

            self.assertEqual([], validation.errors)

    def test_shell_validation_does_not_execute_help(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            skill_dir = Path(temporary_directory) / "wow-migrate"
            scripts_dir = skill_dir / "scripts"
            scripts_dir.mkdir(parents=True)
            marker = Path(temporary_directory) / "side-effect"
            script = scripts_dir / "unsafe-help.sh"
            script.write_text(
                "#!/usr/bin/env bash\n"
                "set -euo pipefail\n"
                "if [[ \"${1:-}\" == \"--help\" ]]; then\n"
                f"  touch {marker}\n"
                "fi\n",
                encoding="utf-8",
            )
            script.chmod(0o755)
            validation = validator.Validation()

            validator.validate_shell_scripts(skill_dir, validation)

            self.assertEqual([], validation.errors)
            self.assertFalse(marker.exists())

    def test_mutating_behavior_requires_evidence_contract(self) -> None:
        case = self.isolated_case(mode="mutating")
        case["fixture"]["writeAllow"] = ["src/**"]
        validation = validator.Validation()

        validator.validate_behavior_case(case, {"wow-debug"}, validation)

        self.assertTrue(any("mutating case requires" in error for error in validation.errors))

    def test_mutating_behavior_reports_non_object_assertion_without_crashing(self) -> None:
        case = self.isolated_case(mode="mutating")
        case["fixture"]["writeAllow"] = ["src/**"]
        case["assertions"] = [42]
        validation = validator.Validation()

        validator.validate_behavior_case(case, {"wow-debug"}, validation)

        self.assertTrue(
            any("expected an object" in error for error in validation.errors),
            validation.errors,
        )

    def test_behavior_validator_rejects_every_json_type_without_crashing(self) -> None:
        invalid_values = [None, False, 0, 1.5, "INVALID", [], {}]
        for field in ("mode", "fixture.kind", "fixture.baseRevision"):
            field_values = (
                invalid_values[1:]
                if field == "fixture.baseRevision"
                else invalid_values
            )
            for invalid in field_values:
                with self.subTest(field=field, value=invalid):
                    case = self.isolated_case(mode="read-only")
                    if field == "mode":
                        case["mode"] = invalid
                    else:
                        fixture = case["fixture"]
                        assert isinstance(fixture, dict)
                        fixture[field.removeprefix("fixture.")] = invalid
                    validation = validator.Validation()

                    validator.validate_behavior_case(case, {"wow-debug"}, validation)

                    self.assertTrue(validation.errors)

        for field in ("assertion.type", "trace.event"):
            for invalid in invalid_values:
                with self.subTest(field=field, value=invalid):
                    case = self.isolated_case(mode="read-only")
                    if field == "assertion.type":
                        case["assertions"] = [{"type": invalid}]
                    else:
                        case["assertions"] = [
                            {
                                "type": "trace.order",
                                "events": [
                                    {"event": invalid, "pattern": "src/.*"},
                                    {"event": "read", "pattern": "src/.*"},
                                ],
                            }
                        ]
                    validation = validator.Validation()

                    validator.validate_behavior_case(case, {"wow-debug"}, validation)

                    self.assertTrue(validation.errors)

    def test_mutating_trace_requires_same_command_for_red_and_green(self) -> None:
        failing_command = ["./gradlew", ":wow-core:test"]
        unrelated_command = ["git", "status"]
        assertions = [
            {"type": "command.exit", "argv": unrelated_command, "exitCode": 0}
        ]
        orders = [
            [
                {"event": "command", "argv": failing_command, "exitCode": "nonzero"},
                {"event": "write", "pattern": "src/.*"},
                {"event": "command", "argv": unrelated_command, "exitCode": 0},
            ]
        ]
        validation = validator.Validation()

        validator.validate_mutating_policy(
            assertions,
            {"diff.nonEmpty", "artifact.changed", "command.exit", "trace.order"},
            orders,
            "case:1",
            validation,
        )

        self.assertTrue(
            any("same command argv" in error for error in validation.errors),
            validation.errors,
        )

    def test_mutating_command_exit_must_match_proven_green_command(self) -> None:
        proven_command = ["./gradlew", ":wow-core:test"]
        assertions = [
            {"type": "command.exit", "argv": ["git", "status"], "exitCode": 0}
        ]
        orders = [
            [
                {"event": "command", "argv": proven_command, "exitCode": "nonzero"},
                {"event": "write", "pattern": "src/.*"},
                {"event": "command", "argv": proven_command, "exitCode": 0},
            ]
        ]
        validation = validator.Validation()

        validator.validate_mutating_policy(
            assertions,
            {"diff.nonEmpty", "artifact.changed", "command.exit", "trace.order"},
            orders,
            "case:1",
            validation,
        )

        self.assertTrue(
            any("must match the GREEN argv" in error for error in validation.errors),
            validation.errors,
        )

    def test_read_only_behavior_rejects_mutating_assertion(self) -> None:
        case = self.isolated_case(mode="read-only")
        case["assertions"].append({"type": "diff.nonEmpty", "value": True})
        validation = validator.Validation()

        validator.validate_behavior_case(case, {"wow-debug"}, validation)

        self.assertTrue(
            any("declares mutating assertions" in error for error in validation.errors)
        )

    def test_json_boolean_is_not_an_integer_exit_code(self) -> None:
        validation = validator.Validation()

        validator.validate_assertion(
            {"type": "process.exitCode", "value": False}, "case:1", validation
        )

        self.assertTrue(any("integer" in error for error in validation.errors))

    def test_command_evidence_rejects_shell_wrapper(self) -> None:
        validation = validator.Validation()

        validator.validate_assertion(
            {
                "type": "command.exit",
                "argv": ["bash", "-c", "true # ./gradlew :wow-core:test"],
                "exitCode": 0,
            },
            "case:1",
            validation,
        )

        self.assertTrue(any("shell-wrapper" in error for error in validation.errors))

    def test_command_evidence_rejects_absolute_and_env_shell_wrappers(self) -> None:
        for argv in (
            ["/bin/bash", "-lc", "./gradlew :wow-core:test"],
            ["/usr/bin/env", "bash", "-c", "./gradlew :wow-core:test"],
            ["/usr/bin/env", "-S", "zsh -lc './gradlew :wow-core:test'"],
        ):
            with self.subTest(argv=argv):
                validation = validator.Validation()
                validator.validate_assertion(
                    {"type": "command.exit", "argv": argv, "exitCode": 0},
                    "case:1",
                    validation,
                )
                self.assertTrue(
                    any("shell-wrapper" in error for error in validation.errors),
                    validation.errors,
                )

    def test_copied_fixture_repository_requires_non_empty_string(self) -> None:
        case = self.copied_read_only_case("fixture", "none")
        case["fixture"]["repository"] = 123
        validation = validator.Validation()

        validator.validate_behavior_case(case, {"wow-debug"}, validation)

        self.assertTrue(
            any("repository must be a non-empty string" in error for error in validation.errors)
        )

    @staticmethod
    def base_assertions() -> list[dict[str, object]]:
        return [
            {"type": "activation.primarySkill", "value": True},
            {"type": "workspace.unchanged", "value": True},
            {"type": "sandbox.noExternalRead", "value": True},
            {"type": "sandbox.noExternalMutation", "value": True},
            {"type": "process.exitCode", "value": 0},
        ]

    @classmethod
    def isolated_case(cls, mode: str) -> dict[str, object]:
        return {
            "__source__": "/tmp/evals/behavior.jsonl:1",
            "schemaVersion": 2,
            "id": "case",
            "skill": "wow-debug",
            "mode": mode,
            "fixture": {
                "fixtureId": "case",
                "kind": "isolated-git-worktree",
                "repository": ".",
                "revision": "EVAL_SUBJECT",
                "setup": "none",
                "initialState": "clean",
                "writeAllow": [],
            },
            "prompt": "Diagnose the failure.",
            "assertions": cls.base_assertions(),
            "tags": [],
        }

    @classmethod
    def copied_read_only_case(
        cls, repository: str, setup: str, evals_dir: Path | None = None
    ) -> dict[str, object]:
        source_root = evals_dir or Path("/tmp/evals")
        case = cls.isolated_case(mode="read-only")
        case["__source__"] = f"{source_root / 'behavior.jsonl'}:1"
        case["fixture"] = {
            "fixtureId": "case",
            "kind": "copied-directory",
            "repository": repository,
            "revision": "CONTENT_SHA256",
            "setup": setup,
            "initialState": "clean",
            "writeAllow": [],
        }
        case["assertions"] = cls.base_assertions()
        return case


if __name__ == "__main__":
    unittest.main()
