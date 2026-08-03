#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path
from typing import Any

if __package__:
    from . import wow_skill_runner as _runner
else:
    import wow_skill_runner as _runner

for _exported_name in _runner.__all__:
    globals()[_exported_name] = getattr(_runner, _exported_name)
del _exported_name

__all__ = [*_runner.__all__, "parse_args", "list_cases", "print_result", "main"]


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare and verify black-box Wow Skill evaluation runs."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Wow repository root (default: inferred from this script).",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    list_parser = subparsers.add_parser("list", help="List available eval case ids.")
    list_parser.add_argument(
        "--suite", choices=("all", "activation", "behavior"), default="all"
    )

    prepare = subparsers.add_parser(
        "prepare", help="Prepare one isolated fixture and adapter request."
    )
    prepare.add_argument("--case", required=True, dest="case_id")
    prepare.add_argument("--subject", help="Exact commit-ish for EVAL_SUBJECT.")
    prepare.add_argument("--base", help="Exact commit-ish for EVAL_BASE.")
    prepare.add_argument("--output", type=Path, required=True)
    prepare.add_argument(
        "--adapter-key",
        type=Path,
        required=True,
        help="Protected adapter trust key used to seal the prepared run.",
    )

    verify = subparsers.add_parser(
        "verify", help="Verify adapter trace plus runner-owned workspace evidence."
    )
    verify.add_argument("--run-dir", type=Path, required=True)
    verify.add_argument("--evidence", type=Path, required=True)
    verify.add_argument(
        "--adapter-key",
        type=Path,
        help="Protected adapter trust key; without it verification is UNSUPPORTED.",
    )

    cleanup = subparsers.add_parser(
        "cleanup", help="Remove only the runner-owned fixture workspace."
    )
    cleanup.add_argument("--run-dir", type=Path, required=True)
    cleanup.add_argument(
        "--adapter-key",
        type=Path,
        required=True,
        help="Protected trust key used for cleanup and recovery idempotency.",
    )
    cleanup.add_argument(
        "--force-recovery",
        action="store_true",
        help="Recover fixed runner-owned paths when the run marker is damaged.",
    )
    cleanup.add_argument(
        "--source-repo",
        type=Path,
        help="Explicit source repository required with --force-recovery.",
    )
    return parser.parse_args(argv)


def list_cases(cases: dict[str, dict[str, Any]], suite: str) -> None:
    for case_id, case in sorted(cases.items()):
        if suite == "all" or case["__suite__"] == suite:
            print(f"{case_id}\t{case['__suite__']}\t{case.get('mode', '-')}")


def print_result(value: dict[str, Any]) -> None:
    rendered = json.dumps(value, ensure_ascii=False, sort_keys=True)
    try:
        print(rendered)
    except UnicodeError:
        print(json.dumps(value, ensure_ascii=True, sort_keys=True))


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repo_root = resolve_descriptor_path(
            str(args.repo_root), "source repository path"
        )
        if args.command == "list":
            list_cases(load_cases(repo_root), args.suite)
            return 0
        if args.command == "prepare":
            trust = load_adapter_trust(args.adapter_key)
            assert trust is not None
            cases = load_cases(repo_root)
            case = cases.get(args.case_id)
            if case is None:
                raise EvalError(f"unknown eval case: {args.case_id}")
            output = ensure_output_directory(args.output, repo_root)
            marker = prepare_case(
                repo_root, output, case, args.subject, args.base, trust
            )
            print_result(
                {
                    "runId": marker["runId"],
                    "caseId": marker["caseId"],
                    "request": str(output / REQUEST_FILE),
                    "requestSha256": marker["requestSha256"],
                    "runDir": str(output),
                    "status": "PREPARED",
                }
            )
            return 0
        if args.command == "verify":
            trust = load_adapter_trust(args.adapter_key)
            result = verify_run(
                repo_root, args.run_dir, args.evidence, trust
            )
            print_result(result)
            return 1 if result["status"] == "FAIL" else 0
        trust = load_adapter_trust(args.adapter_key)
        print_result(
            cleanup_run(
                args.run_dir,
                trust,
                force_recovery=args.force_recovery,
                source_repo=args.source_repo,
            )
        )
        return 0
    except UnsupportedEvidence as error:
        print_result({"status": "UNSUPPORTED", "error": str(error)})
        return 3
    except EvalError as error:
        print_result({"status": "ERROR", "error": str(error)})
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
