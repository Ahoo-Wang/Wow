from __future__ import annotations

import contextlib
import hashlib
import hmac
import io
import json
import os
import runpy
import shutil
import subprocess
import tempfile
import typing
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
os.sys.path.insert(0, str(SCRIPT_DIR))

import run_wow_skill_evals as runner  # noqa: E402
import wow_skill_runner.cleanup as cleanup_module  # noqa: E402
import wow_skill_runner.oracles as oracles_module  # noqa: E402
import wow_skill_runner.prepare as prepare_module  # noqa: E402
import wow_skill_runner.state as state_module  # noqa: E402


class RunWowSkillEvalsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.cases = runner.load_cases(REPO_ROOT)
        self.trust = runner.AdapterTrust(
            key_id="test-key",
            adapter_name="test-adapter",
            adapter_version="1.0.0",
            secret=b"trusted-test-adapter-secret-32!!",
        )

    def test_loads_all_current_cases(self) -> None:
        self.assertEqual(38, len(self.cases))
        self.assertEqual("behavior", self.cases["B09-migrate-local-platform"]["__suite__"])

    def test_cli_annotations_are_resolvable(self) -> None:
        typing.get_type_hints(runner.list_cases)
        typing.get_type_hints(runner.print_result)

    def test_runner_module_cli_runs_without_site_packages(self) -> None:
        environment = dict(os.environ)
        environment["PYTHONDONTWRITEBYTECODE"] = "1"

        result = subprocess.run(
            [
                os.sys.executable,
                "-S",
                "-m",
                "scripts.run_wow_skill_evals",
                "--repo-root",
                str(REPO_ROOT),
                "list",
                "--suite",
                "behavior",
            ],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
            env=environment,
            timeout=30,
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("B10-migrate-data-rehearsal", result.stdout)

    def test_load_cases_rejects_invalid_skill_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo_root = Path(temporary_directory) / "repo"
            shutil.copytree(REPO_ROOT / "skills", repo_root / "skills")
            schema_source = REPO_ROOT / runner.TRACE_SCHEMA_PATH
            schema_target = repo_root / runner.TRACE_SCHEMA_PATH
            schema_target.parent.mkdir(parents=True)
            shutil.copy2(schema_source, schema_target)
            skill_file = repo_root / "skills/wow-develop/SKILL.md"
            skill_file.write_text(
                skill_file.read_text(encoding="utf-8").replace(
                    'name: "wow-develop"', "name: wow-develop", 1
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(runner.EvalError, "double-quoted"):
                runner.load_cases(repo_root)

    def test_read_object_wraps_invalid_utf8_as_eval_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "evidence.json"
            path.write_bytes(b"\xff")

            with self.assertRaisesRegex(runner.EvalError, "invalid evidence"):
                runner.read_object(path, "evidence")

    def test_json_and_canonicalization_limits_return_eval_errors(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            huge_integer = root / "huge-integer.json"
            huge_integer.write_text('{"value":' + "9" * 5000 + "}")
            with self.assertRaises(runner.EvalError):
                runner.read_object(huge_integer, "huge integer")

            deep_array = root / "deep-array.json"
            deep_array.write_text('{"value":' + "[" * 10000 + "]" * 10000 + "}")
            with self.assertRaises(runner.EvalError):
                runner.read_object(deep_array, "deep array")

            fifo = root / "evidence.pipe"
            os.mkfifo(fifo)
            with self.assertRaisesRegex(runner.EvalError, "regular file"):
                runner.read_object(fifo, "FIFO evidence")

        with self.assertRaisesRegex(runner.EvalError, "canonicalized"):
            runner.canonical_json({"value": "\ud800"})

    def test_invalid_key_and_git_paths_return_eval_errors(self) -> None:
        for invalid_path in ("\0", "\ud800"):
            with self.subTest(path=repr(invalid_path)):
                with self.assertRaises(runner.EvalError):
                    runner.load_adapter_trust(Path(invalid_path))
        with self.assertRaises(runner.EvalError):
            runner.resolve_commit(REPO_ROOT, "\0", "subject")

    def test_adapter_key_permissions_are_checked_on_open_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            key = Path(temporary_directory) / "adapter-key.json"
            runner.write_json(
                key,
                {
                    "schemaVersion": 1,
                    "keyId": self.trust.key_id,
                    "adapterName": self.trust.adapter_name,
                    "adapterVersion": self.trust.adapter_version,
                    "secretHex": self.trust.secret.hex(),
                },
            )
            key.chmod(0o644)

            with self.assertRaisesRegex(runner.EvalError, "readable by group"):
                runner.load_adapter_trust(key)

    def test_path_resolution_runtime_errors_are_structured(self) -> None:
        operations = (
            lambda: runner.ensure_output_directory(Path("run"), REPO_ROOT),
            lambda: runner.load_run(Path("run"), trust=self.trust),
            lambda: runner.resolve_descriptor_path(
                "evidence.json", "adapter evidence path"
            ),
            lambda: runner.normalize_access_path(
                Path("/tmp/file"),
                "read",
                Path("/tmp/workspace"),
                Path("/tmp/plugin"),
            ),
        )
        for operation in operations:
            with self.subTest(operation=operation):
                with mock.patch.object(
                    Path, "resolve", side_effect=RuntimeError("symlink loop")
                ):
                    with self.assertRaises(runner.EvalError):
                        operation()

    def test_hidden_evaluation_implementation_is_not_readable(self) -> None:
        hidden_paths = (
            "skills/wow-develop/evals",
            "skills/wow-develop/evals/behavior.jsonl",
            "scripts/run_wow_skill_evals.py",
            "scripts/validate_wow_skills.py",
            "scripts/wow_skill_eval_contract.py",
            "scripts/wow_skill_runner",
            "scripts/wow_skill_runner/oracles.py",
            "scripts/wow_skill_runner/assertions.py",
            "scripts/wow_skill_validator",
            "scripts/wow_skill_validator/evals.py",
            "scripts/test_run_wow_skill_evals.py",
            "scripts/test_validate_wow_skills.py",
        )
        for relative_path in hidden_paths:
            with self.subTest(relative_path=relative_path):
                with self.assertRaisesRegex(runner.EvalError, "hidden eval content"):
                    runner.normalize_access_path(
                        REPO_ROOT / relative_path,
                        "read",
                        REPO_ROOT,
                        REPO_ROOT / "staged-plugin",
                    )

    def test_prepare_and_cleanup_copied_fixture_and_sanitized_plugin(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "run"
            output = runner.ensure_output_directory(run_dir, REPO_ROOT)

            marker = runner.prepare_case(
                REPO_ROOT,
                output,
                self.cases["B09-migrate-local-platform"],
                None,
                None,
                self.trust,
            )

            workspace = Path(marker["workspace"])
            plugin = Path(marker["pluginStaging"])
            self.assertTrue((workspace / "verify-local-migration.sh").is_file())
            self.assertTrue((workspace / "verify-local-migration.py").is_file())
            self.assertTrue((plugin / runner.STAGED_TRACE_SCHEMA).is_file())
            self.assertFalse(any(path.name == "evals" for path in plugin.rglob("*")))
            self.assertEqual("", runner.require_git(workspace, ["status", "--porcelain"]))
            result = runner.cleanup_run(run_dir, self.trust)
            self.assertEqual("CLEAN", result["status"])
            self.assertFalse(workspace.exists())
            self.assertFalse(plugin.exists())

    def test_prepare_applies_review_setup_against_subject_base(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "run"
            output = runner.ensure_output_directory(run_dir, REPO_ROOT)

            marker = runner.prepare_case(
                REPO_ROOT,
                output,
                self.cases["B05-review-fix-order"],
                "HEAD",
                None,
                self.trust,
            )

            workspace = Path(marker["workspace"])
            cart = workspace / "example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/cart/Cart.kt"
            request = json.loads((run_dir / runner.REQUEST_FILE).read_text(encoding="utf-8"))
            self.assertIn("state.items.size <= MAX_CART_ITEM_SIZE", cart.read_text())
            self.assertEqual(marker["fixture"]["sourceSha"], marker["fixture"]["baseSha"])
            self.assertEqual(marker["fixture"]["baseSha"], request["revision"]["baseSha"])
            self.assertNotIn("assertions", json.dumps(request))
            self.assertEqual("", runner.require_git(workspace, ["status", "--porcelain"]))
            runner.cleanup_run(run_dir, self.trust)

    def test_prepare_canonicalizes_relative_source_repository(self) -> None:
        previous_directory = Path.cwd()
        try:
            with tempfile.TemporaryDirectory() as temporary_directory:
                run_dir = Path(temporary_directory) / "run"
                os.chdir(REPO_ROOT.parent)
                relative_repo = Path(REPO_ROOT.name)
                output = runner.ensure_output_directory(run_dir, relative_repo)
                marker = runner.prepare_case(
                    relative_repo,
                    output,
                    self.cases["A15-none-kotlin"],
                    None,
                    None,
                    self.trust,
                )
                os.chdir(temporary_directory)

                self.assertEqual(str(REPO_ROOT), marker["sourceRepo"])
                loaded = runner.load_run(run_dir, trust=self.trust)
                self.assertEqual(str(REPO_ROOT), loaded["sourceRepo"])
                runner.cleanup_run(run_dir, self.trust)
        finally:
            os.chdir(previous_directory)

    def test_review_fixture_uses_standalone_git_clone(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory,
                "B04-review-readonly",
                subject="HEAD",
                base="HEAD~1",
            )
            workspace = Path(marker["workspace"])

            self.assertTrue((workspace / ".git").is_dir())
            self.assertFalse((workspace / ".git/objects/info/alternates").exists())
            self.assertEqual("", runner.require_git(workspace, ["remote"]))
            self.assertEqual(
                marker["fixture"]["baseSha"],
                runner.resolve_commit(
                    workspace, str(marker["fixture"]["baseSha"]), "clone base"
                ),
            )
            base_sha = str(marker["fixture"]["baseSha"])
            evidence = self.evidence(
                marker,
                [
                    {"seq": 1, "type": "activation", "skill": "wow-review", "primary": True},
                    {
                        "seq": 2,
                        "type": "command",
                        "argv": ["git", "merge-base", "HEAD", base_sha],
                        "cwd": str(workspace),
                        "exitCode": 0,
                    },
                    {
                        "seq": 3,
                        "type": "command",
                        "argv": ["git", "diff", "--name-only", f"{base_sha}...HEAD", "--"],
                        "cwd": str(workspace),
                        "exitCode": 0,
                    },
                    {
                        "seq": 4,
                        "type": "command",
                        "argv": [
                            "git",
                            "diff",
                            "--no-ext-diff",
                            "--no-textconv",
                            f"{base_sha}...HEAD",
                            "--",
                        ],
                        "cwd": str(workspace),
                        "exitCode": 0,
                    },
                ],
                "no blocking findings",
            )
            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )
            self.assertEqual("PASS", result["status"], result["failures"])
            runner.cleanup_run(run_dir, self.trust)
            self.assertFalse(workspace.exists())

    def test_standalone_clone_transfers_unreferenced_detached_commits(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            repository = root / "repository"
            repository.mkdir()
            runner.require_git(repository, ["init", "-b", "main"])
            tracked = repository / "tracked.txt"
            tracked.write_text("base\n", encoding="utf-8")
            runner.require_git(repository, ["add", "tracked.txt"])
            runner.require_git(
                repository,
                [
                    "-c",
                    "user.name=Wow Skill Eval",
                    "-c",
                    "user.email=wow-skill-eval@invalid.local",
                    "commit",
                    "-m",
                    "base",
                ],
            )
            base_sha = runner.resolve_commit(repository, "HEAD", "base")
            runner.require_git(repository, ["checkout", "--detach", base_sha])
            tracked.write_text("detached subject\n", encoding="utf-8")
            runner.require_git(repository, ["add", "tracked.txt"])
            runner.require_git(
                repository,
                [
                    "-c",
                    "user.name=Wow Skill Eval",
                    "-c",
                    "user.email=wow-skill-eval@invalid.local",
                    "commit",
                    "-m",
                    "unreferenced subject",
                ],
            )
            subject_sha = runner.resolve_commit(repository, "HEAD", "subject")
            runner.require_git(repository, ["checkout", "main"])
            self.assertEqual(
                "",
                runner.require_git(
                    repository,
                    ["branch", "--format=%(refname)", "--contains", subject_sha],
                ),
            )

            workspace = root / "standalone"
            fixture = {
                "fixtureId": "detached-clone",
                "baseRevision": "EVAL_BASE",
                "setup": "none",
            }
            evidence = runner.prepare_clone(
                repository,
                workspace,
                fixture,
                subject_sha,
                base_sha,
                root,
            )

            self.assertEqual(subject_sha, evidence["sourceSha"])
            self.assertEqual(base_sha, evidence["baseSha"])
            self.assertEqual(subject_sha, runner.resolve_commit(workspace, "HEAD", "HEAD"))
            self.assertEqual(base_sha, runner.resolve_commit(workspace, base_sha, "base"))
            self.assertEqual("", runner.require_git(workspace, ["remote"]))
            self.assertFalse((workspace / ".git/objects/info/alternates").exists())

    def test_activation_requires_exact_wow_skill_set(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A01-develop-aggregate")
            evidence = self.evidence(
                marker,
                events=[
                    {"seq": 1, "type": "activation", "skill": "wow-develop", "primary": True},
                    {"seq": 2, "type": "activation", "skill": "wow-review", "primary": False},
                ],
                output="",
                activation_only=True,
            )
            evidence_path = self.write_evidence(run_dir, evidence)

            result = runner.verify_run(REPO_ROOT, run_dir, evidence_path, self.trust)

            self.assertEqual("FAIL", result["status"])
            self.assertTrue(any("activation mismatch" in item for item in result["failures"]))
            runner.cleanup_run(run_dir, self.trust)

    def test_negative_activation_allows_unrelated_primary_skill(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            evidence = self.evidence(
                marker,
                events=[
                    {"seq": 1, "type": "activation", "skill": "kotlin-debug", "primary": True}
                ],
                output="",
                activation_only=True,
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("PASS", result["status"], result["failures"])
            runner.cleanup_run(run_dir, self.trust)

    def test_activation_rejects_tool_execution_after_routing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A01-develop-aggregate")
            evidence = self.evidence(
                marker,
                events=[
                    {"seq": 1, "type": "activation", "skill": "wow-develop", "primary": True},
                    {"seq": 2, "type": "command", "argv": ["git", "status"], "cwd": ".", "exitCode": 0},
                ],
                output="",
                activation_only=True,
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("FAIL", result["status"])
            self.assertTrue(any("continued" in item for item in result["failures"]))
            runner.cleanup_run(run_dir, self.trust)

    def test_cleanup_survives_tampered_adapter_request(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            (run_dir / runner.REQUEST_FILE).write_text("{}\n", encoding="utf-8")

            with self.assertRaises(runner.EvalError):
                runner.load_run(run_dir, trust=self.trust)
            result = runner.cleanup_run(run_dir, self.trust)

            self.assertEqual("CLEAN", result["status"])
            self.assertFalse(workspace.exists())

    def test_verify_rejects_cleaned_run_with_structured_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            evidence_path = self.write_evidence(
                run_dir,
                self.evidence(marker, [], "", activation_only=True),
            )
            runner.cleanup_run(run_dir, self.trust)

            with self.assertRaisesRegex(runner.EvalError, "cleaned"):
                runner.verify_run(REPO_ROOT, run_dir, evidence_path, self.trust)

    def test_cleanup_rejects_invalid_marker_paths_without_traceback(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, _ = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            marker_path = run_dir / runner.RUN_MARKER
            original = json.loads(marker_path.read_text(encoding="utf-8"))
            for field in ("workspace", "pluginStaging"):
                for invalid in ({}, "\0"):
                    with self.subTest(field=field, value=repr(invalid)):
                        mutated = dict(original)
                        mutated[field] = invalid
                        runner.write_json(
                            marker_path,
                            runner.seal_run_marker(mutated, self.trust),
                        )
                        with self.assertRaises(runner.EvalError):
                            runner.cleanup_run(run_dir, self.trust)
            runner.write_json(marker_path, original)
            runner.cleanup_run(run_dir, self.trust)

    def test_cleanup_rejects_forged_cleaned_marker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            plugin = Path(marker["pluginStaging"])
            marker_path = run_dir / runner.RUN_MARKER
            forged = dict(marker)
            forged["workspace"] = None
            forged["pluginStaging"] = None
            forged["cleaned"] = True
            runner.write_json(marker_path, forged)

            with self.assertRaisesRegex(runner.EvalError, "run seal"):
                runner.cleanup_run(run_dir, self.trust)

            self.assertTrue(workspace.exists())
            self.assertTrue(plugin.exists())
            recovered = runner.cleanup_run(
                run_dir,
                self.trust,
                force_recovery=True,
                source_repo=REPO_ROOT,
            )
            self.assertTrue(recovered["recovery"])
            self.assertFalse(workspace.exists())
            self.assertFalse(plugin.exists())
            workspace.mkdir()
            plugin.mkdir()
            (workspace / "sentinel").write_text("keep\n", encoding="utf-8")
            (plugin / "sentinel").write_text("keep\n", encoding="utf-8")

            repeated = runner.cleanup_run(
                run_dir,
                self.trust,
                force_recovery=True,
                source_repo=REPO_ROOT,
            )

            self.assertTrue(repeated["alreadyClean"])
            self.assertTrue((workspace / "sentinel").is_file())
            self.assertTrue((plugin / "sentinel").is_file())

    def test_force_recovery_rejects_forged_tombstone(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            plugin = Path(marker["pluginStaging"])
            runner.write_json(
                run_dir / runner.RECOVERY_TOMBSTONE,
                {
                    "schemaVersion": 1,
                    "runDirSha256": runner.sha256_bytes(
                        str(run_dir.resolve()).encode("utf-8")
                    ),
                    "sourceRepo": str(REPO_ROOT),
                    "status": "CLEAN",
                    "seal": {
                        "algorithm": "hmac-sha256",
                        "keyId": self.trust.key_id,
                        "signature": "0" * 64,
                    },
                },
            )

            with self.assertRaisesRegex(runner.EvalError, "tombstone seal"):
                runner.cleanup_run(
                    run_dir,
                    self.trust,
                    force_recovery=True,
                    source_repo=REPO_ROOT,
                )

            self.assertTrue(workspace.exists())
            self.assertTrue(plugin.exists())
            (run_dir / runner.RECOVERY_TOMBSTONE).unlink()
            runner.cleanup_run(run_dir, self.trust)

    def test_cleanup_uses_actual_worktree_registration(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            marker_path = run_dir / runner.RUN_MARKER
            incorrect = dict(marker)
            incorrect["worktreeRegistered"] = False
            runner.write_json(
                marker_path, runner.seal_run_marker(incorrect, self.trust)
            )

            result = runner.cleanup_run(run_dir, self.trust)

            self.assertEqual("CLEAN", result["status"])
            self.assertFalse(workspace.exists())
            self.assertNotIn(
                str(workspace), runner.registered_worktree_paths(REPO_ROOT)
            )

    def test_cleanup_is_idempotent_without_deleting_reused_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, _ = self.prepare(temporary_directory, "A15-none-kotlin")
            activation_workspace = run_dir / "workspace"
            activation_workspace.mkdir()
            (activation_workspace / "sentinel").write_text("keep\n", encoding="utf-8")

            runner.cleanup_run(run_dir, self.trust)

            self.assertTrue((activation_workspace / "sentinel").is_file())
            reused_plugin = run_dir / runner.STAGED_PLUGIN_DIR
            reused_plugin.mkdir()
            (reused_plugin / "sentinel").write_text("keep\n", encoding="utf-8")
            runner.cleanup_run(run_dir, self.trust)
            self.assertTrue((reused_plugin / "sentinel").is_file())

    def test_cleanup_handles_newline_in_worktree_path(self) -> None:
        with tempfile.TemporaryDirectory(prefix="wow-eval-\n") as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            self.assertIn(str(workspace), runner.registered_worktree_paths(REPO_ROOT))

            runner.cleanup_run(run_dir, self.trust)

            self.assertNotIn(
                str(workspace), runner.registered_worktree_paths(REPO_ROOT)
            )

    def test_cleanup_copied_fixture_does_not_require_live_source_git(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B09-migrate-local-platform"
            )
            workspace = Path(marker["workspace"])

            with mock.patch.object(
                cleanup_module,
                "registered_worktree_paths",
                side_effect=AssertionError("copied fixtures must not query source Git"),
            ):
                result = runner.cleanup_run(run_dir, self.trust)

            self.assertEqual("CLEAN", result["status"])
            self.assertFalse(workspace.exists())

    def test_cleanup_rejects_plugin_replaced_by_regular_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            plugin = Path(marker["pluginStaging"])
            shutil.rmtree(plugin)
            plugin.write_text("not a directory\n", encoding="utf-8")

            with self.assertRaisesRegex(runner.EvalError, "not a real directory"):
                runner.cleanup_run(run_dir, self.trust)

            plugin.unlink()
            plugin.mkdir()
            runner.cleanup_run(run_dir, self.trust)

    def test_cleanup_unlinks_self_referential_plugin_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            plugin = Path(marker["pluginStaging"])
            shutil.rmtree(plugin)
            plugin.symlink_to(plugin, target_is_directory=True)

            result = runner.cleanup_run(run_dir, self.trust)

            self.assertEqual("CLEAN", result["status"])
            self.assertFalse(plugin.is_symlink())

    def test_cleanup_rejects_hard_linked_marker_without_mutating_external_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            run_dir, _ = self.prepare(temporary_directory, "A15-none-kotlin")
            marker_path = run_dir / runner.RUN_MARKER
            original = marker_path.read_bytes()
            external = root / "external-marker.json"
            external.write_bytes(original)
            marker_path.unlink()
            os.link(external, marker_path)

            with self.assertRaisesRegex(runner.EvalError, "hard-linked"):
                runner.cleanup_run(run_dir, self.trust)
            self.assertEqual(original, external.read_bytes())

            marker_path.unlink()
            marker_path.write_bytes(original)
            runner.cleanup_run(run_dir, self.trust)

    def test_atomic_json_write_replaces_hardlink_without_touching_peer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            external = root / "external.json"
            target = root / "target.json"
            external.write_text('{"original":true}\n', encoding="utf-8")
            os.link(external, target)

            runner.write_json(target, {"replacement": True})

            self.assertEqual('{"original":true}\n', external.read_text(encoding="utf-8"))
            self.assertEqual(
                {"replacement": True},
                json.loads(target.read_text(encoding="utf-8")),
            )

    def test_prepared_request_rejects_surrogate_prompt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, _ = self.prepare(temporary_directory, "A15-none-kotlin")
            request_path = run_dir / runner.REQUEST_FILE
            request = json.loads(request_path.read_text(encoding="utf-8"))
            request["prompt"] = "\ud800"
            request["promptSha256"] = "0" * 64
            request_path.write_text(
                json.dumps(request, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            marker_path = run_dir / runner.RUN_MARKER
            marker = json.loads(marker_path.read_text(encoding="utf-8"))
            marker["requestSha256"] = runner.sha256_file(request_path)
            runner.write_json(
                marker_path, runner.seal_run_marker(marker, self.trust)
            )

            with self.assertRaisesRegex(runner.EvalError, "valid UTF-8"):
                runner.load_run(run_dir, trust=self.trust)
            runner.cleanup_run(run_dir, self.trust)

    def test_output_creation_failure_is_wrapped_as_eval_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            parent = Path(temporary_directory) / "regular-file"
            parent.write_text("not a directory\n", encoding="utf-8")

            with self.assertRaisesRegex(runner.EvalError, "invalid eval output"):
                runner.ensure_output_directory(parent / "run", REPO_ROOT)

    def test_main_returns_structured_error_for_invalid_repo_path(self) -> None:
        stdout = io.StringIO()

        with contextlib.redirect_stdout(stdout):
            exit_code = runner.main(["--repo-root", "\0", "list"])

        self.assertEqual(2, exit_code)
        self.assertEqual("ERROR", json.loads(stdout.getvalue())["status"])

    def test_oracle_runtime_symlink_is_rejected_and_safely_cleaned(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            external = root / "external"
            external.mkdir()
            standalone_runtime = root / "standalone" / runner.ORACLE_RUNTIME_DIR
            standalone_runtime.parent.mkdir()
            standalone_runtime.symlink_to(external, target_is_directory=True)

            with self.assertRaisesRegex(runner.EvalError, "symlink"):
                runner.prepare_oracle_temp(standalone_runtime)
            self.assertEqual([], list(external.iterdir()))

            run_dir, _ = self.prepare(str(root / "prepared"), "A15-none-kotlin")
            runtime = run_dir / runner.ORACLE_RUNTIME_DIR
            runtime.symlink_to(external, target_is_directory=True)
            result = runner.cleanup_run(run_dir, self.trust)

            self.assertEqual("CLEAN", result["status"])
            self.assertFalse(runtime.exists())
            self.assertTrue(external.is_dir())

    def test_frozen_contract_and_plugin_are_tamper_evident(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A01-develop-aggregate")
            contract = run_dir / runner.CONTRACT_FILE
            original = contract.read_text(encoding="utf-8")
            contract.write_text("{}\n", encoding="utf-8")
            with self.assertRaises(runner.EvalError):
                runner.load_run(run_dir, trust=self.trust)
            contract.write_text(original, encoding="utf-8")
            staged_skill = Path(marker["pluginStaging"]) / "wow-develop/SKILL.md"
            staged_skill.write_text(staged_skill.read_text() + "\nchanged\n", encoding="utf-8")
            with self.assertRaises(runner.EvalError):
                runner.load_run(run_dir, trust=self.trust)
            runner.cleanup_run(run_dir, self.trust)

    def test_local_migration_oracle_accepts_only_real_target_contract(self) -> None:
        source = REPO_ROOT / "skills/wow-migrate/evals/fixtures/v6-service"
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = Path(temporary_directory) / "fixture"
            shutil.copytree(source, fixture)
            build_file = fixture / "build.gradle.kts"
            build_file.write_text(
                build_file.read_text(encoding="utf-8")
                .replace('version "2.3.20"', 'version "2.4.10"')
                .replace('version "4.0.6"', 'version "4.1.0"')
                .replace(":6.21.5", ":8.9.6"),
                encoding="utf-8",
            )

            result = subprocess.run(
                [str(fixture / "verify-local-migration.sh"), str(fixture)],
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("oracle passed", result.stdout)

    def test_local_migration_oracle_rejects_target_versions_in_comments(self) -> None:
        source = REPO_ROOT / "skills/wow-migrate/evals/fixtures/v6-service"
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = Path(temporary_directory) / "fixture"
            shutil.copytree(source, fixture)
            with (fixture / "build.gradle.kts").open("a", encoding="utf-8") as stream:
                stream.write(
                    '\n// kotlin("jvm") version "2.4.10"\n'
                    '// id("org.springframework.boot") version "4.1.0"\n'
                    '// implementation("me.ahoo.wow:wow-spring-boot-starter:8.9.6")\n'
                )

            result = subprocess.run(
                [str(fixture / "verify-local-migration.sh"), str(fixture)],
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )

            self.assertNotEqual(0, result.returncode)

    def test_local_migration_oracle_rejects_block_comment_or_string_decoys(self) -> None:
        source = REPO_ROOT / "skills/wow-migrate/evals/fixtures/v6-service"
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = Path(temporary_directory) / "fixture"
            shutil.copytree(source, fixture)
            (fixture / "build.gradle.kts").write_text(
                '''val decoy = """
                kotlin("jvm") version "2.4.10"
                id("org.springframework.boot") version "4.1.0"
                implementation("me.ahoo.wow:wow-spring-boot-starter:8.9.6")
                jvmToolchain(17)
                """
                /* target declarations are not active Gradle DSL */
                ''',
                encoding="utf-8",
            )

            result = subprocess.run(
                [str(fixture / "verify-local-migration.sh"), str(fixture)],
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )

            self.assertNotEqual(0, result.returncode)

    def test_data_rehearsal_oracle_proves_resume_outputs_and_idempotency(self) -> None:
        source = REPO_ROOT / "skills/wow-migrate/evals/fixtures/data-cutover"
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = Path(temporary_directory) / "fixture"
            shutil.copytree(source, fixture)
            baseline = subprocess.run(
                [
                    str(fixture / "verify-data-rehearsal.sh"),
                    str(fixture),
                    "complete",
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )
            self.assertNotEqual(0, baseline.returncode)
            migration = fixture / "migrate-data.py"
            migration.write_text(
                '''from __future__ import annotations
	import argparse
	import base64
	import hashlib
	import json
	import sys
	from collections import defaultdict
	from pathlib import Path

	BUCKETS = 128

	def digest(value):
	    return hashlib.sha256(
	        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
	    ).hexdigest()

	def load_lines(path):
	    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]

	def b64(value):
	    return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")

	def utf16_units(value):
	    encoded = value.encode("utf-16-be")
	    return [int.from_bytes(encoded[index:index + 2], "big") for index in range(0, len(encoded), 2)]

	def bucket(value):
	    result = 0
	    for code_unit in utf16_units(value):
	        result = (31 * result + code_unit) & 0xFFFFFFFF
	    return result % BUCKETS

	def sortable(value):
	    return "".join(f"{code_unit:04x}" for code_unit in utf16_units(value))

	def map_record(record):
	    aggregate_id = record["aggregateId"]
	    tenant_id = record["tenantId"]
	    slot = bucket(aggregate_id)
	    scope = f"{b64(record['resolvedContextAlias'])}.{b64(record['aggregateName'])}"
	    hash_tag = f"{{v2:es:{scope}:{slot}}}"
	    event_key = f"{hash_tag}:{b64(aggregate_id)}.{b64(tenant_id)}"
	    return {
	        "aggregateIdIndexType": "zset",
	        "canonicalAggregateIdIndexKey": f"{hash_tag}:ids",
	        "canonicalAggregateIdIndexMember": f"{sortable(aggregate_id)}.{b64(tenant_id)}",
	        "canonicalAggregateIdIndexScore": 0,
	        "canonicalEventKey": event_key,
	        "canonicalRequestIndexKey": f"{event_key}:req_idx",
	        "eventType": "zset",
	        "member": record["member"],
	        "requestId": record["requestId"],
	        "requestIndexType": "set",
	        "score": record["score"],
	    }

	def reconcile(source, target, target_path, source_sha):
	    event_streams = defaultdict(list)
	    request_indexes = defaultdict(set)
	    aggregate_indexes = set()
	    for record in target:
	        event_streams[record["canonicalEventKey"]].append(
	            {"member": record["member"], "score": record["score"]}
	        )
	        request_indexes[record["canonicalRequestIndexKey"]].add(record["requestId"])
	        aggregate_indexes.add(
	            (
	                record["canonicalAggregateIdIndexKey"],
	                record["canonicalAggregateIdIndexMember"],
	                record["canonicalAggregateIdIndexScore"],
	            )
	        )
	    ordered_events = [
	        {"key": key, "members": sorted(members, key=lambda member: member["score"])}
	        for key, members in sorted(event_streams.items())
	    ]
	    ordered_requests = [
	        {"key": key, "members": sorted(members)}
	        for key, members in sorted(request_indexes.items())
	    ]
	    ordered_indexes = [
	        {"key": key, "member": member, "score": score}
	        for key, member, score in sorted(aggregate_indexes)
	    ]
	    summaries = []
	    requests_by_key = {item["key"]: item["members"] for item in ordered_requests}
	    for stream in ordered_events:
	        members = stream["members"]
	        requests = requests_by_key[f"{stream['key']}:req_idx"]
	        summaries.append(
	            {
	                "canonicalEventKey": stream["key"],
	                "firstVersion": members[0]["score"],
	                "lastVersion": members[-1]["score"],
	                "memberCount": len(members),
	                "orderedMemberScoreSha256": digest(members),
	                "requestIdCount": len(requests),
	                "requestIdsSha256": digest(requests),
	            }
	        )
	    scan = sorted(
	        {
	            (
	                record["resolvedContextAlias"],
	                record["aggregateName"],
	                record["aggregateId"],
	                record["tenantId"],
	                bucket(record["aggregateId"]),
	            )
	            for record in source
	        }
	    )
	    return {
	        "aggregateIdIndexBucketCount": BUCKETS,
	        "aggregateIdIndexEntryCount": len(ordered_indexes),
	        "aggregateIdIndexSha256": digest(ordered_indexes),
	        "aggregateIdScanCount": len(scan),
	        "aggregateIdScanSha256": digest(scan),
	        "orderedEventMemberScoreSha256": digest(ordered_events),
	        "requestIndexEntryCount": sum(len(item["members"]) for item in ordered_requests),
	        "requestIndexSha256": digest(ordered_requests),
	        "sourceEventCount": len(target),
	        "sourceRequestIdMismatchCount": 0,
	        "sourceSha256": source_sha,
	        "sourceStreamCount": len(ordered_events),
	        "streamSummaries": summaries,
	        "targetEventCount": len(target),
	        "targetRequestIdMismatchCount": 0,
	        "targetSha256": hashlib.sha256(target_path.read_bytes()).hexdigest(),
	        "targetStreamCount": len(ordered_events),
	    }

parser = argparse.ArgumentParser()
parser.add_argument("--source", required=True)
parser.add_argument("--target", required=True)
parser.add_argument("--checkpoint", required=True)
parser.add_argument("--reconciliation", required=True)
parser.add_argument("--fail-after", type=int)
args = parser.parse_args()
source_path = Path(args.source)
source_bytes = source_path.read_bytes()
source_sha = hashlib.sha256(source_bytes).hexdigest()
	source = load_lines(source_path)
	target = [map_record(record) for record in source]
target_path = Path(args.target)
target_path.parent.mkdir(parents=True, exist_ok=True)
checkpoint_path = Path(args.checkpoint)
if checkpoint_path.exists():
    checkpoint = json.loads(checkpoint_path.read_text())
    if checkpoint["sourceSha256"] != source_sha:
        raise SystemExit(30)
    if checkpoint["targetSha256"] != hashlib.sha256(target_path.read_bytes()).hexdigest():
        raise SystemExit(33)
    if checkpoint["complete"]:
        raise SystemExit(0)
    start = checkpoint["lastSourceIndex"] + 1
	    existing = load_lines(target_path)
    if existing != target[:start]:
        raise SystemExit(31)
else:
    start = 0
    target_path.write_text("")
stop = args.fail_after if args.fail_after is not None else len(target)
if stop < start or stop > len(target):
    raise SystemExit(32)
with target_path.open("a", encoding="utf-8") as stream:
    for row in target[start:stop]:
        stream.write(
            json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            + "\\n"
        )
target_sha = hashlib.sha256(target_path.read_bytes()).hexdigest()
checkpoint_path.write_text(
    json.dumps(
        {
            "complete": stop == len(target),
            "lastSourceIndex": stop - 1,
            "sourceSha256": source_sha,
            "targetEventCount": stop,
            "targetSha256": target_sha,
        },
        sort_keys=True,
        separators=(",", ":"),
    ) + "\\n"
)
if args.fail_after is not None:
    Path(args.reconciliation).unlink(missing_ok=True)
    raise SystemExit(23)
Path(args.reconciliation).write_text(
    json.dumps(
	        reconcile(source, target, target_path, source_sha),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ) + "\\n"
)
'''.replace("\n\t", "\n"),
                encoding="utf-8",
            )
            common = [
                os.sys.executable,
                str(migration),
                "--source",
                "source/events.jsonl",
                "--target",
                "rehearsal/target.jsonl",
                "--checkpoint",
                "rehearsal/checkpoint.json",
                "--reconciliation",
                "rehearsal/reconciliation.json",
            ]
            interrupted = subprocess.run(
                [*common, "--fail-after", "2"], cwd=fixture, check=False
            )
            self.assertNotEqual(0, interrupted.returncode)
            intermediate = subprocess.run(
                [
                    str(fixture / "verify-data-rehearsal.sh"),
                    str(fixture),
                    "interrupted",
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )
            self.assertEqual(0, intermediate.returncode, intermediate.stderr)
            target_path = fixture / "rehearsal/target.jsonl"
            checkpoint_path = fixture / "rehearsal/checkpoint.json"
            reconciliation_path = fixture / "rehearsal/reconciliation.json"
            partial_target = target_path.read_bytes()
            partial_checkpoint = checkpoint_path.read_bytes()
            oracle = runpy.run_path(str(fixture / "verify-data-rehearsal.py"))
            source_records = oracle["load_json_lines"](fixture / "source/events.jsonl")
            full_target = oracle["expected_target"](source_records)
            target_path.write_text(
                "".join(
                    json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
                    + "\n"
                    for row in full_target
                ),
                encoding="utf-8",
            )
            checkpoint_path.write_text(
                json.dumps(
                    oracle["expected_checkpoint"](len(full_target), True, target_path),
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n",
                encoding="utf-8",
            )
            reconciliation_path.write_text(
                json.dumps(
                    oracle["expected_reconciliation"](full_target, target_path),
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n",
                encoding="utf-8",
            )
            restarted_from_zero = subprocess.run(
                [
                    str(fixture / "verify-data-rehearsal.sh"),
                    str(fixture),
                    "complete",
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )
            self.assertNotEqual(0, restarted_from_zero.returncode)
            self.assertIn("preserve", restarted_from_zero.stderr)
            target_path.write_bytes(partial_target)
            checkpoint_path.write_bytes(partial_checkpoint)
            reconciliation_path.unlink()
            self.assertEqual(0, subprocess.run(common, cwd=fixture, check=False).returncode)
            artifacts = (
                fixture / "rehearsal/target.jsonl",
                fixture / "rehearsal/checkpoint.json",
                fixture / "rehearsal/reconciliation.json",
            )
            before = {artifact: artifact.read_bytes() for artifact in artifacts}
            self.assertEqual(0, subprocess.run(common, cwd=fixture, check=False).returncode)
            self.assertEqual(before, {artifact: artifact.read_bytes() for artifact in artifacts})

            verified = subprocess.run(
                [
                    str(fixture / "verify-data-rehearsal.sh"),
                    str(fixture),
                    "complete",
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )
            self.assertEqual(0, verified.returncode, verified.stderr)
            self.assertIn("oracle passed", verified.stdout)
            self.assertEqual(
                [True, True, False, False],
                [
                    length > 0
                    for length in oracle["resume_probe_lengths"](
                        fixture / "rehearsal/target.jsonl"
                    )
                ],
            )

            target_rows = [
                json.loads(line)
                for line in (fixture / "rehearsal/target.jsonl").read_text().splitlines()
            ]
            self.assertEqual(
                "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:6}:"
                "b3JkZXJAezQyfTrpm6o.dGVuYW50QGVhc3R9",
                target_rows[2]["canonicalEventKey"],
            )
            self.assertEqual(
                "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:6}:"
                "b3JkZXJAezQyfTrpm6o.dGVuYW50QGVhc3R9:req_idx",
                target_rows[2]["canonicalRequestIndexKey"],
            )
            self.assertEqual(
                "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:6}:ids",
                target_rows[2]["canonicalAggregateIdIndexKey"],
            )
            self.assertEqual(
                "006f00720064006500720040007b00340032007d003a96ea."
                "dGVuYW50QGVhc3R9",
                target_rows[2]["canonicalAggregateIdIndexMember"],
            )
            self.assertEqual(0, target_rows[2]["canonicalAggregateIdIndexScore"])
            self.assertEqual("zset", target_rows[2]["eventType"])
            self.assertEqual("set", target_rows[2]["requestIndexType"])
            self.assertEqual("zset", target_rows[2]["aggregateIdIndexType"])
            self.assertEqual(
                target_rows[0]["canonicalEventKey"],
                target_rows[1]["canonicalEventKey"],
            )
            self.assertEqual([1, 2], [target_rows[0]["score"], target_rows[1]["score"]])
            target_rows[0]["canonicalEventKey"] = "wow:event:tenant-a:order:order-1"
            (fixture / "rehearsal/target.jsonl").write_text(
                "".join(
                    json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
                    + "\n"
                    for row in target_rows
                )
            )
            rejected = subprocess.run(
                [
                    str(fixture / "verify-data-rehearsal.sh"),
                    str(fixture),
                    "complete",
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=10,
            )
            self.assertNotEqual(0, rejected.returncode)

    def test_data_rehearsal_codec_round_trips_empty_tenant(self) -> None:
        oracle = runpy.run_path(
            str(
                REPO_ROOT
                / "skills/wow-migrate/evals/fixtures/data-cutover/verify-data-rehearsal.py"
            )
        )
        target = [
            {
                "canonicalAggregateIdIndexKey": (
                    "{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:27}:ids"
                ),
                "canonicalAggregateIdIndexMember": "00690064.",
            }
        ]

        self.assertEqual(
            [("order-service", "order", "id", "", 27)],
            oracle["decoded_aggregate_id_scan"](target),
        )

    def test_cart_capacity_oracle_injects_and_removes_runner_owned_test(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            workspace = root / "workspace"
            workspace.mkdir()
            gradlew = workspace / "gradlew"
            hidden_test = (
                workspace
                / "example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartCapacityEvalOracleTest.kt"
            )
            gradlew.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
            gradlew.chmod(0o755)
            hidden_test.parent.mkdir(parents=True)
            cached_gradle = (
                workspace
                / ".eval-runtime/gradle-home/wrapper/dists/test/gradle-9.0/bin/gradle"
            )
            cached_gradle.parent.mkdir(parents=True)
            cached_gradle.write_text("cached distribution\n", encoding="utf-8")
            (
                workspace / ".eval-runtime/gradle-home/caches/modules-2/files-2.1"
            ).mkdir(parents=True)

            def completed(*args: object, **kwargs: object) -> subprocess.CompletedProcess[str]:
                command = args[0]
                assert isinstance(command, list)
                self.assertIn("--offline", command)
                self.assertIn("--rerun-tasks", command)
                self.assertIn("--no-build-cache", command)
                environment = kwargs["env"]
                assert isinstance(environment, dict)
                self.assertEqual(
                    str(workspace / ".eval-runtime/gradle-home"),
                    environment["GRADLE_USER_HOME"],
                )
                content = hidden_test.read_text(encoding="utf-8")
                self.assertIn("assertEquals(10, MAX_CART_ITEM_SIZE)", content)
                self.assertIn("existing product remains accepted", content)
                self.assertIn("next distinct product is rejected", content)
                return subprocess.CompletedProcess([], 0, "", "")

            with mock.patch.object(oracles_module.subprocess, "run", side_effect=completed):
                self.assertTrue(
                    runner.cart_capacity_oracle(
                        workspace, 10, root / runner.ORACLE_RUNTIME_DIR
                    )
                )
            self.assertFalse(hidden_test.exists())

    def test_cart_capacity_oracle_executes_offline_without_network(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            workspace = root / "workspace"
            workspace.mkdir()
            gradlew = workspace / "gradlew"
            hidden_test = (
                workspace
                / "example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartCapacityEvalOracleTest.kt"
            )
            gradlew.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
case " $* " in
  *" --offline "*) ;;
  *) exit 41 ;;
esac
test -f example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartCapacityEvalOracleTest.kt
grep -q 'assertEquals(10, MAX_CART_ITEM_SIZE)' example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartCapacityEvalOracleTest.kt
""",
                encoding="utf-8",
            )
            gradlew.chmod(0o755)
            hidden_test.parent.mkdir(parents=True)
            cached_gradle = (
                workspace
                / ".eval-runtime/gradle-home/wrapper/dists/test/gradle-9.0/bin/gradle"
            )
            cached_gradle.parent.mkdir(parents=True)
            cached_gradle.write_text("cached distribution\n", encoding="utf-8")
            (
                workspace / ".eval-runtime/gradle-home/caches/modules-2/files-2.1"
            ).mkdir(parents=True)

            self.assertTrue(
                runner.cart_capacity_oracle(
                    workspace, 10, root / runner.ORACLE_RUNTIME_DIR
                )
            )
            self.assertFalse(hidden_test.exists())

    def test_cart_capacity_oracle_reports_missing_cache_as_unsupported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            workspace = root / "workspace"
            workspace.mkdir()
            gradlew = workspace / "gradlew"
            gradlew.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            gradlew.chmod(0o755)

            with self.assertRaisesRegex(
                runner.UnsupportedEvidence, "workspace-local Gradle"
            ):
                runner.cart_capacity_oracle(
                    workspace, 10, root / runner.ORACLE_RUNTIME_DIR
                )

    def test_gradle_infrastructure_failure_recognizes_missing_java_runtime(self) -> None:
        self.assertTrue(
            runner.gradle_infrastructure_failure(
                "ERROR: JAVA_HOME is not set and no 'java' command could be found "
                "in your PATH."
            )
        )
        self.assertFalse(
            runner.gradle_infrastructure_failure(
                "e: Cart.kt:42: Kotlin compilation failed"
            )
        )

    def test_failed_prepare_restores_empty_output_for_retry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "run"
            output = runner.ensure_output_directory(run_dir, REPO_ROOT)

            with self.assertRaises(runner.EvalError):
                runner.prepare_case(
                    REPO_ROOT,
                    output,
                    self.cases["B01-develop-source-lookup"],
                    "refs/heads/does-not-exist",
                    None,
                    self.trust,
                )

            self.assertEqual([], list(output.iterdir()))
            marker = runner.prepare_case(
                REPO_ROOT,
                output,
                self.cases["B01-develop-source-lookup"],
                "HEAD",
                None,
                self.trust,
            )
            self.assertEqual("B01-develop-source-lookup", marker["caseId"])
            runner.cleanup_run(run_dir, self.trust)

    def test_failed_activation_prepare_does_not_require_source_git(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source_repo = root / "source-package"
            shutil.copytree(REPO_ROOT / "skills", source_repo / "skills")
            schema_source = REPO_ROOT / runner.TRACE_SCHEMA_PATH
            schema_target = source_repo / runner.TRACE_SCHEMA_PATH
            schema_target.parent.mkdir(parents=True)
            shutil.copy2(schema_source, schema_target)
            output = runner.ensure_output_directory(root / "run", source_repo)

            with mock.patch.object(
                prepare_module,
                "prepare_activation",
                side_effect=runner.EvalError("simulated activation prepare failure"),
            ):
                with self.assertRaises(runner.EvalError):
                    runner.prepare_case(
                        source_repo,
                        output,
                        self.cases["A15-none-kotlin"],
                        None,
                        None,
                        self.trust,
                    )

            self.assertEqual([], list(output.iterdir()))

    def test_trace_rejects_empty_activation_skill(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A01-develop-aggregate")
            evidence = self.evidence(
                marker,
                [{"seq": 1, "type": "activation", "skill": "", "primary": True}],
                "",
                activation_only=True,
            )

            with self.assertRaisesRegex(runner.EvalError, "activation event values"):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_trace_rejects_empty_access_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            evidence = self.evidence(
                marker,
                [
                    {
                        "seq": 1,
                        "type": "activation",
                        "skill": "wow-develop",
                        "primary": True,
                    },
                    {"seq": 2, "type": "read", "path": ""},
                ],
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            with self.assertRaisesRegex(runner.EvalError, "invalid read event"):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_trace_rejects_non_string_event_type(self) -> None:
        for invalid in (None, False, 0, 1.5, [], {}):
            with self.subTest(value=invalid):
                with self.assertRaisesRegex(runner.EvalError, "event type"):
                    runner.validate_event_shape(
                        {"seq": 1, "type": invalid},
                        -1,
                    )

    def test_path_normalization_wraps_embedded_nul_as_eval_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            workspace = root / "workspace"
            plugin = root / "plugin"
            workspace.mkdir()
            plugin.mkdir()

            with self.assertRaisesRegex(runner.EvalError, "invalid read path"):
                runner.normalize_access_path(Path("\0"), "read", workspace, plugin)
            with self.assertRaisesRegex(runner.EvalError, "invalid command cwd"):
                runner.normalize_command_cwd(Path("\0"), workspace)
            with self.assertRaisesRegex(runner.EvalError, "invalid command executable"):
                runner.normalize_command_executable(Path("/\0"), "git", workspace)

    def test_read_only_evidence_passes_with_real_workspace_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            evidence = self.evidence(
                marker,
                events=self.b01_events(workspace),
                output=(
                    "onSourcing 方法在恰好一个 value 参数时可省略注解；"
                    "sourcing 禁止调用外部服务或产生副作用。"
                ),
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("PASS", result["status"], result["failures"])
            runner.cleanup_run(run_dir, self.trust)

    def test_missing_workspace_policy_is_unsupported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "B08-migrate-audit")
            workspace = Path(marker["workspace"])
            evidence = self.evidence(
                marker,
                events=[
                    {"seq": 1, "type": "activation", "skill": "wow-migrate", "primary": True},
                    {"seq": 2, "type": "read", "path": str(workspace / "build.gradle.kts")},
                    {"seq": 3, "type": "read", "path": str(workspace / "src/main/resources/application.yml")},
                ],
                output="6.21.5 4.0.6 2.3.20 -> 8.9.6; MISSING EVIDENCE",
                workspace_policy=False,
            )

            with self.assertRaises(runner.UnsupportedEvidence):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_behavior_rejects_activation_only_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "B08-migrate-audit")
            evidence = self.evidence(
                marker,
                events=[],
                output="MISSING EVIDENCE",
                activation_only=True,
            )

            with self.assertRaises(runner.EvalError):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_command_trace_requires_fixture_root_and_real_executable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            events = self.b01_events(workspace)
            events.append(
                {
                    "seq": 4,
                    "type": "command",
                    "argv": ["git", "status"],
                    "cwd": str(workspace / "build"),
                    "exitCode": 0,
                }
            )
            evidence = self.evidence(
                marker,
                events,
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            with self.assertRaises(runner.EvalError):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_command_trace_rejects_executable_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            events = self.b01_events(workspace)
            events.append(
                {
                    "seq": 4,
                    "type": "command",
                    "argv": ["git", "status"],
                    "cwd": str(workspace),
                    "executable": "/bin/echo",
                    "exitCode": 0,
                }
            )
            evidence = self.evidence(
                marker,
                events,
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            with self.assertRaises(runner.EvalError):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_missing_trust_key_is_unsupported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            evidence = self.evidence(marker, [], "", activation_only=True)

            with self.assertRaises(runner.UnsupportedEvidence):
                runner.verify_run(
                    REPO_ROOT, run_dir, self.write_evidence(run_dir, evidence)
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_tampered_attestation_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            evidence = self.evidence(marker, [], "", activation_only=True)
            evidence["output"] = "tampered after signing"

            with self.assertRaises(runner.EvalError):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_signed_request_seals_contract_against_marker_rehash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A01-develop-aggregate")
            evidence = self.evidence(marker, [], "", activation_only=True)
            evidence_path = self.write_evidence(run_dir, evidence)
            original = runner.verify_run(REPO_ROOT, run_dir, evidence_path, self.trust)
            self.assertEqual("FAIL", original["status"])

            contract_path = run_dir / runner.CONTRACT_FILE
            contract = json.loads(contract_path.read_text(encoding="utf-8"))
            contract["expectedSkills"] = []
            runner.write_json(contract_path, contract)
            contract_hash = runner.sha256_file(contract_path)
            request_path = run_dir / runner.REQUEST_FILE
            request = json.loads(request_path.read_text(encoding="utf-8"))
            request["integrity"]["contractSha256"] = contract_hash
            runner.write_json(request_path, request)
            marker_path = run_dir / runner.RUN_MARKER
            tampered_marker = json.loads(marker_path.read_text(encoding="utf-8"))
            tampered_marker["contractSha256"] = contract_hash
            tampered_marker["requestSha256"] = runner.sha256_file(request_path)
            runner.write_json(marker_path, tampered_marker)

            with self.assertRaisesRegex(runner.EvalError, "run seal"):
                runner.verify_run(REPO_ROOT, run_dir, evidence_path, self.trust)

            fresh_evidence = self.evidence(
                tampered_marker, [], "", activation_only=True
            )
            with self.assertRaisesRegex(runner.EvalError, "run seal"):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, fresh_evidence),
                    self.trust,
                )
            recovered = runner.cleanup_run(
                run_dir,
                self.trust,
                force_recovery=True,
                source_repo=REPO_ROOT,
            )
            self.assertTrue(recovered["recovery"])

    def test_signed_request_policy_must_match_frozen_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            request_path = run_dir / runner.REQUEST_FILE
            request = json.loads(request_path.read_text(encoding="utf-8"))
            request["workspacePolicy"]["writeAllow"] = ["**"]
            runner.write_json(request_path, request)
            marker_path = run_dir / runner.RUN_MARKER
            tampered_marker = json.loads(marker_path.read_text(encoding="utf-8"))
            tampered_marker["requestSha256"] = runner.sha256_file(request_path)
            tampered_marker = runner.seal_run_marker(tampered_marker, self.trust)
            runner.write_json(marker_path, tampered_marker)
            evidence = self.evidence(
                tampered_marker,
                self.b01_events(workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            with self.assertRaisesRegex(runner.EvalError, "workspacePolicy"):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_prepared_revision_tree_must_match_sealed_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, _ = self.prepare(
                temporary_directory, "B08-migrate-audit", subject="HEAD"
            )
            request_path = run_dir / runner.REQUEST_FILE
            request = json.loads(request_path.read_text(encoding="utf-8"))
            request["revision"]["baselineTreeSha256"] = "0" * 64
            runner.write_json(request_path, request)
            marker_path = run_dir / runner.RUN_MARKER
            marker = json.loads(marker_path.read_text(encoding="utf-8"))
            marker["fixture"]["baselineTreeSha256"] = "0" * 64
            marker["requestSha256"] = runner.sha256_file(request_path)
            runner.write_json(
                marker_path, runner.seal_run_marker(marker, self.trust)
            )

            with self.assertRaisesRegex(runner.EvalError, "baselineTreeSha256"):
                runner.load_run(run_dir, trust=self.trust)

            runner.cleanup_run(run_dir, self.trust)

    def test_verification_uses_frozen_package_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            evidence = self.evidence(marker, [], "", activation_only=True)
            evidence_path = self.write_evidence(run_dir, evidence)

            with mock.patch.object(
                state_module,
                "load_cases",
                side_effect=AssertionError("live source package must not be read"),
            ):
                result = runner.verify_run(
                    REPO_ROOT, run_dir, evidence_path, self.trust
                )

            self.assertEqual("PASS", result["status"])
            runner.cleanup_run(run_dir, self.trust)

    def test_run_seal_binds_adapter_identity_and_version(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            rotated = runner.AdapterTrust(
                key_id=self.trust.key_id,
                adapter_name=self.trust.adapter_name,
                adapter_version="2.0.0",
                secret=self.trust.secret,
            )
            evidence = self.evidence(marker, [], "", activation_only=True)

            with self.assertRaisesRegex(runner.EvalError, "adapter identity"):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    rotated,
                )

            runner.cleanup_run(run_dir, self.trust)

    def test_run_seal_rejects_plugin_and_descriptor_rehash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            staged_skill = Path(marker["pluginStaging"]) / "wow-develop/SKILL.md"
            staged_skill.write_text(
                staged_skill.read_text(encoding="utf-8") + "\nchanged\n",
                encoding="utf-8",
            )
            plugin_hash = runner.hash_tree(Path(marker["pluginStaging"]))
            request_path = run_dir / runner.REQUEST_FILE
            request = json.loads(request_path.read_text(encoding="utf-8"))
            request["integrity"]["pluginTreeSha256"] = plugin_hash
            runner.write_json(request_path, request)
            marker_path = run_dir / runner.RUN_MARKER
            tampered_marker = json.loads(marker_path.read_text(encoding="utf-8"))
            tampered_marker["pluginTreeSha256"] = plugin_hash
            tampered_marker["requestSha256"] = runner.sha256_file(request_path)
            runner.write_json(marker_path, tampered_marker)
            evidence = self.evidence(
                tampered_marker, [], "", activation_only=True
            )

            with self.assertRaisesRegex(runner.EvalError, "run seal"):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            recovered = runner.cleanup_run(
                run_dir,
                self.trust,
                force_recovery=True,
                source_repo=REPO_ROOT,
            )
            self.assertTrue(recovered["recovery"])

    def test_signed_request_seals_baseline_against_rebasing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            evidence = self.evidence(
                marker,
                self.b01_events(workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )
            evidence_path = self.write_evidence(run_dir, evidence)
            readme = workspace / "README.md"
            readme.write_text(readme.read_text(encoding="utf-8") + "\nchanged\n")
            failed = runner.verify_run(REPO_ROOT, run_dir, evidence_path, self.trust)
            self.assertEqual("FAIL", failed["status"])

            manifest_path = run_dir / runner.BASELINE_MANIFEST_FILE
            runner.write_json(manifest_path, runner.tree_manifest(workspace))
            manifest_hash = runner.sha256_file(manifest_path)
            request_path = run_dir / runner.REQUEST_FILE
            request = json.loads(request_path.read_text(encoding="utf-8"))
            request["integrity"]["baselineManifestSha256"] = manifest_hash
            runner.write_json(request_path, request)
            marker_path = run_dir / runner.RUN_MARKER
            tampered_marker = json.loads(marker_path.read_text(encoding="utf-8"))
            tampered_marker["baselineManifestSha256"] = manifest_hash
            tampered_marker["requestSha256"] = runner.sha256_file(request_path)
            runner.write_json(marker_path, tampered_marker)

            with self.assertRaisesRegex(runner.EvalError, "run seal"):
                runner.verify_run(REPO_ROOT, run_dir, evidence_path, self.trust)
            recovered = runner.cleanup_run(
                run_dir,
                self.trust,
                force_recovery=True,
                source_repo=REPO_ROOT,
            )
            self.assertTrue(recovered["recovery"])

    def test_signed_request_rejects_workspace_redirect(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            first_root = Path(temporary_directory) / "first"
            second_root = Path(temporary_directory) / "second"
            first_root.mkdir()
            second_root.mkdir()
            first_run, first_marker = self.prepare(
                str(first_root), "B01-develop-source-lookup", subject="HEAD"
            )
            second_run, second_marker = self.prepare(
                str(second_root), "B01-develop-source-lookup", subject="HEAD"
            )
            first_workspace = Path(first_marker["workspace"])
            evidence = self.evidence(
                first_marker,
                self.b01_events(first_workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )
            evidence_path = self.write_evidence(first_run, evidence)
            readme = first_workspace / "README.md"
            readme.write_text(readme.read_text(encoding="utf-8") + "\nchanged\n")
            marker_path = first_run / runner.RUN_MARKER
            original_marker = json.loads(marker_path.read_text(encoding="utf-8"))
            redirected_marker = dict(original_marker)
            redirected_marker["workspace"] = second_marker["workspace"]
            runner.write_json(marker_path, redirected_marker)

            with self.assertRaisesRegex(runner.EvalError, "run seal"):
                runner.verify_run(REPO_ROOT, first_run, evidence_path, self.trust)

            runner.write_json(marker_path, original_marker)
            runner.cleanup_run(first_run, self.trust)
            runner.cleanup_run(second_run, self.trust)

    def test_workspace_manifest_rejects_fifo(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            os.mkfifo(workspace / "outside-allowlist.pipe")
            evidence = self.evidence(
                marker,
                self.b01_events(workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            with self.assertRaisesRegex(runner.EvalError, "unsupported file type"):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_workspace_manifest_detects_empty_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            (workspace / "outside-allowlist-directory").mkdir()
            evidence = self.evidence(
                marker,
                self.b01_events(workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("FAIL", result["status"])
            self.assertTrue(any("writeAllow" in item for item in result["failures"]))
            runner.cleanup_run(run_dir, self.trust)

    def test_reverted_read_only_write_still_fails_trace_policy(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            events = self.b01_events(workspace)
            events.append(
                {"seq": 4, "type": "write", "path": str(workspace / "README.md")}
            )
            evidence = self.evidence(
                marker,
                events,
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("FAIL", result["status"])
            self.assertTrue(any("write trace" in failure for failure in result["failures"]))
            runner.cleanup_run(run_dir, self.trust)

    def test_ignored_file_change_cannot_bypass_workspace_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            ignored = workspace / ".idea/unreported.txt"
            ignored.parent.mkdir(exist_ok=True)
            ignored.write_text("hidden mutation\n", encoding="utf-8")
            evidence = self.evidence(
                marker,
                self.b01_events(workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("FAIL", result["status"])
            self.assertTrue(any("writeAllow" in item for item in result["failures"]))
            runner.cleanup_run(run_dir, self.trust)

    def test_workspace_diff_includes_unstaged_new_and_ignored_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            workspace = Path(temporary_directory)
            runner.require_git(workspace, ["init", "-b", "main"])
            (workspace / ".gitignore").write_text("ignored/\n", encoding="utf-8")
            (workspace / "tracked.txt").write_text("baseline\n", encoding="utf-8")
            runner.require_git(workspace, ["add", ".gitignore", "tracked.txt"])
            runner.require_git(
                workspace,
                [
                    "-c",
                    "user.name=Wow Skill Eval",
                    "-c",
                    "user.email=wow-skill-eval@invalid.local",
                    "commit",
                    "-m",
                    "baseline",
                ],
            )
            baseline = runner.resolve_commit(workspace, "HEAD", "baseline")
            (workspace / "migrate-data.py").write_text(
                'canonicalEventKey = "{v2:es:scope:1}:identity"\n',
                encoding="utf-8",
            )
            ignored = workspace / "ignored/target.jsonl"
            ignored.parent.mkdir()
            ignored.write_text(
                '{"canonicalRequestIndexKey":"key:req_idx"}\n',
                encoding="utf-8",
            )

            diff = runner.workspace_diff(
                workspace,
                baseline,
                ["ignored/target.jsonl", "migrate-data.py"],
            )

            self.assertIn("canonicalEventKey", diff)
            self.assertIn("canonicalRequestIndexKey", diff)
            self.assertIn("migrate-data.py", diff)
            self.assertIn("ignored/target.jsonl", diff)

    def test_declared_gradle_output_does_not_fail_read_only_source_check(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            generated = workspace / "wow-core/build/test-results/result.txt"
            generated.parent.mkdir(parents=True)
            generated.write_text("runner tool output\n", encoding="utf-8")
            evidence = self.evidence(
                marker,
                self.b01_events(workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("PASS", result["status"], result["failures"])
            runner.cleanup_run(run_dir, self.trust)

    def test_gradle_init_script_is_never_treated_as_tool_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            control = workspace / ".eval-runtime/gradle-home/init.gradle"
            control.parent.mkdir(parents=True)
            control.write_text("allprojects { }\n", encoding="utf-8")
            evidence = self.evidence(
                marker,
                self.b01_events(workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("FAIL", result["status"])
            self.assertTrue(any("writeAllow" in item for item in result["failures"]))
            runner.cleanup_run(run_dir, self.trust)

    def test_assume_unchanged_file_change_is_detected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            readme = workspace / "README.md"
            readme.write_text(readme.read_text() + "\nmutated\n", encoding="utf-8")
            runner.require_git(workspace, ["update-index", "--assume-unchanged", "README.md"])
            evidence = self.evidence(
                marker,
                self.b01_events(workspace),
                "onSourcing 恰好一个 value 参数；外部服务属于副作用。",
            )

            result = runner.verify_run(
                REPO_ROOT,
                run_dir,
                self.write_evidence(run_dir, evidence),
                self.trust,
            )

            self.assertEqual("FAIL", result["status"])
            self.assertTrue(any("writeAllow" in item for item in result["failures"]))
            runner.cleanup_run(run_dir, self.trust)

    def test_boolean_trace_sequence_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A15-none-kotlin")
            evidence = self.evidence(
                marker,
                [{"seq": True, "type": "activation", "skill": "other", "primary": True}],
                "",
                activation_only=True,
            )

            with self.assertRaises(runner.EvalError):
                runner.verify_run(
                    REPO_ROOT,
                    run_dir,
                    self.write_evidence(run_dir, evidence),
                    self.trust,
                )
            runner.cleanup_run(run_dir, self.trust)

    def test_cli_returns_nonzero_for_assertion_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(temporary_directory, "A01-develop-aggregate")
            evidence = self.evidence(marker, [], "", activation_only=True)
            evidence_path = self.write_evidence(run_dir, evidence)
            key_path = Path(temporary_directory) / "adapter-key.json"
            key_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "keyId": self.trust.key_id,
                        "adapterName": self.trust.adapter_name,
                        "adapterVersion": self.trust.adapter_version,
                        "secretHex": self.trust.secret.hex(),
                    }
                ),
                encoding="utf-8",
            )
            key_path.chmod(0o600)

            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = runner.main(
                    [
                        "--repo-root",
                        str(REPO_ROOT),
                        "verify",
                        "--run-dir",
                        str(run_dir),
                        "--evidence",
                        str(evidence_path),
                        "--adapter-key",
                        str(key_path),
                    ]
                )

            self.assertEqual(1, exit_code)
            runner.cleanup_run(run_dir, self.trust)

    def test_cleanup_removes_registration_when_workspace_was_deleted(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir, marker = self.prepare(
                temporary_directory, "B01-develop-source-lookup", subject="HEAD"
            )
            workspace = Path(marker["workspace"])
            shutil.rmtree(workspace)

            result = runner.cleanup_run(run_dir, self.trust)

            self.assertEqual("CLEAN", result["status"])
            registered = runner.require_git(REPO_ROOT, ["worktree", "list", "--porcelain"])
            self.assertNotIn(str(workspace), registered)

    def prepare(
        self,
        temporary_directory: str,
        case_id: str,
        *,
        subject: str | None = None,
        base: str | None = None,
    ) -> tuple[Path, dict[str, object]]:
        run_dir = Path(temporary_directory) / "run"
        output = runner.ensure_output_directory(run_dir, REPO_ROOT)
        marker = runner.prepare_case(
            REPO_ROOT, output, self.cases[case_id], subject, base, self.trust
        )
        return run_dir, marker

    def evidence(
        self,
        marker: dict[str, object],
        events: list[dict[str, object]],
        output: str,
        *,
        activation_only: bool = False,
        workspace_policy: bool = True,
    ) -> dict[str, object]:
        capabilities = ["activation-trace", "tool-trace", "command-policy"]
        if workspace_policy:
            capabilities.append("workspace-policy")
        if activation_only:
            capabilities.append("activation-only")
        complete_events: list[dict[str, object]] = []
        for original in events:
            event = dict(original)
            if event.get("type") == "command" and "executable" not in event:
                argv = event["argv"]
                assert isinstance(argv, list)
                argv0 = str(argv[0])
                if argv0.startswith("./"):
                    workspace = marker["workspace"]
                    assert isinstance(workspace, str)
                    event["executable"] = str(
                        (Path(workspace) / argv0.removeprefix("./")).resolve()
                    )
                else:
                    executable = shutil.which(argv0)
                    assert executable is not None
                    event["executable"] = executable
            complete_events.append(event)
        evidence: dict[str, object] = {
            "schemaVersion": runner.TRACE_SCHEMA_VERSION,
            "runId": marker["runId"],
            "requestSha256": marker["requestSha256"],
            "adapter": {
                "name": self.trust.adapter_name,
                "version": self.trust.adapter_version,
                "capabilities": capabilities,
                "freshTask": True,
                "promptExact": True,
            },
            "output": output,
            "processExitCode": 0,
            "events": complete_events,
            "sandbox": {
                "externalReadBlocked": True,
                "externalMutationBlocked": True,
                "networkBlocked": True,
                "connectorsBlocked": True,
                "evalContentBlocked": True,
                "activationOnly": activation_only,
                "commandPolicyEnforced": True,
            },
        }
        signature = hmac.new(
            self.trust.secret,
            runner.evidence_signature_payload(evidence),
            hashlib.sha256,
        ).hexdigest()
        evidence["attestation"] = {
            "algorithm": "hmac-sha256",
            "keyId": self.trust.key_id,
            "signature": signature,
        }
        return evidence

    @staticmethod
    def write_evidence(run_dir: Path, evidence: dict[str, object]) -> Path:
        evidence_path = run_dir / "evidence.json"
        evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
        return evidence_path

    @staticmethod
    def b01_events(workspace: Path) -> list[dict[str, object]]:
        return [
            {"seq": 1, "type": "activation", "skill": "wow-develop", "primary": True},
            {
                "seq": 2,
                "type": "read",
                "path": str(
                    workspace
                    / "wow-api/src/main/kotlin/me/ahoo/wow/api/annotation/OnSourcing.kt"
                ),
            },
            {
                "seq": 3,
                "type": "read",
                "path": str(
                    workspace
                    / "wow-core/src/main/kotlin/me/ahoo/wow/modeling/annotation/StateAggregateMetadataParser.kt"
                ),
            },
        ]


if __name__ == "__main__":
    unittest.main()
