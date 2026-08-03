"""Assertion registry and suite-specific behavior verification."""

from __future__ import annotations

import fnmatch
import re
import shutil
from pathlib import Path
from typing import Any

from .git import require_git, workspace_diff, workspace_head
from .io import read_object, tree_manifest
from .model import (
    AssertionContext,
    BASELINE_MANIFEST_FILE,
    EvalError,
    ORACLE_RUNTIME_DIR,
    TOOL_CONTROL_WRITE_DENY,
    TOOL_WRITE_ALLOW,
    UnsupportedEvidence,
)
from .oracles import cart_capacity_oracle

__all__ = [
    'primary_skills',
    'activated_wow_skills',
    'command_text',
    'event_text',
    'manifest_changed_paths',
    'matches_exit',
    'expected_argv',
    'expected_command_executable',
    'command_event_matches',
    'find_event',
    'check_trace_order',
    'check_command_exit',
    'check_pattern_event',
    'check_sandbox',
    'check_primary_skill',
    'check_workspace_unchanged',
    'check_diff_non_empty',
    'check_artifact_changed',
    'check_diff_regex',
    'check_cart_capacity_branches',
    'review_changed_files',
    'check_reviewed_changed_file',
    'check_reviewed_all_changed_files',
    'check_trace_pattern',
    'check_command_assertion',
    'check_order_assertion',
    'check_output_regex',
    'check_output_not_regex',
    'check_sandbox_assertion',
    'check_process_exit',
    'ASSERTION_CHECKERS',
    'assertion_passes',
    'verify_write_allow',
    'verify_trace_write_allow',
    'verify_activation_case',
    'verify_behavior_case',
]


def primary_skills(events: list[dict[str, Any]]) -> list[str]:
    return [
        str(event["skill"])
        for event in events
        if event["type"] == "activation" and event["primary"] is True
    ]


def activated_wow_skills(
    case: dict[str, Any], events: list[dict[str, Any]]
) -> list[str]:
    known = set(case["__known_skills__"])
    return [
        str(event["skill"])
        for event in events
        if event["type"] == "activation" and event["skill"] in known
    ]


def command_text(event: dict[str, Any]) -> str:
    return " ".join(event["argv"])


def event_text(event: dict[str, Any]) -> str:
    if event["type"] in {"read", "write"}:
        return str(event["path"])
    if event["type"] == "command":
        return command_text(event)
    return str(event.get("skill", ""))


def manifest_changed_paths(
    baseline: dict[str, dict[str, Any]], current: dict[str, dict[str, Any]]
) -> list[str]:
    all_paths = set(baseline) | set(current)
    return sorted(path for path in all_paths if baseline.get(path) != current.get(path))


def matches_exit(actual: int, expected: Any) -> bool:
    if expected == "nonzero":
        return actual != 0
    return actual == expected


def expected_argv(
    value: list[str], marker: dict[str, Any]
) -> list[str]:
    base_sha = marker.get("fixture", {}).get("baseSha")
    expanded: list[str] = []
    for argument in value:
        if "EVAL_BASE" in argument:
            if not isinstance(base_sha, str):
                raise EvalError("command assertion requires a prepared EVAL_BASE")
            argument = argument.replace("EVAL_BASE", base_sha)
        expanded.append(argument)
    return expanded


def expected_command_executable(argv0: str, marker: dict[str, Any]) -> str:
    try:
        if argv0.startswith("./"):
            workspace = marker.get("workspace")
            if not isinstance(workspace, str):
                raise EvalError("workspace-relative command assertion has no workspace")
            executable = (
                Path(workspace) / argv0.removeprefix("./")
            ).resolve(strict=False)
            return (
                "workspace/"
                + executable.relative_to(Path(workspace).resolve()).as_posix()
            )
        resolved = shutil.which(argv0)
        if resolved is None:
            raise UnsupportedEvidence(
                f"runner cannot resolve asserted executable {argv0!r} on PATH"
            )
        return str(Path(resolved).resolve(strict=False))
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"cannot resolve asserted executable {argv0!r}: {error}") from error


def command_event_matches(
    event: dict[str, Any], argv: list[str], marker: dict[str, Any]
) -> bool:
    return (
        event["type"] == "command"
        and event["argv"] == argv
        and event["cwd"] == "."
        and event["executable"] == expected_command_executable(argv[0], marker)
    )


def find_event(
    events: list[dict[str, Any]],
    start: int,
    descriptor: dict[str, Any],
    marker: dict[str, Any],
) -> int | None:
    event_type = descriptor["event"]
    for index in range(start, len(events)):
        event = events[index]
        if event["type"] != event_type:
            continue
        if event_type == "command":
            argv = expected_argv(descriptor["argv"], marker)
            if not command_event_matches(event, argv, marker):
                continue
            if not matches_exit(event["exitCode"], descriptor["exitCode"]):
                continue
        elif not re.search(descriptor["pattern"], event_text(event)):
            continue
        return index
    return None


def check_trace_order(
    descriptors: list[dict[str, Any]],
    events: list[dict[str, Any]],
    marker: dict[str, Any],
) -> bool:
    next_index = 0
    for descriptor in descriptors:
        found = find_event(events, next_index, descriptor, marker)
        if found is None:
            return False
        next_index = found + 1
    return True


def check_command_exit(
    assertion: dict[str, Any],
    events: list[dict[str, Any]],
    marker: dict[str, Any],
) -> bool:
    argv = expected_argv(assertion["argv"], marker)
    return any(
        command_event_matches(event, argv, marker)
        and matches_exit(event["exitCode"], assertion["exitCode"])
        for event in events
    )


def check_pattern_event(
    assertion_type: str, pattern_text: str, events: list[dict[str, Any]]
) -> bool:
    event_type = "read" if "read" in assertion_type.lower() else "write"
    found = any(
        event["type"] == event_type
        and re.search(pattern_text, event_text(event))
        for event in events
    )
    return not found if ".not" in assertion_type else found


def check_sandbox(evidence: dict[str, Any], assertion_type: str) -> bool:
    sandbox = evidence.get("sandbox")
    if not isinstance(sandbox, dict):
        raise UnsupportedEvidence("adapter did not provide sandbox enforcement evidence")
    required = {
        "externalReadBlocked",
        "externalMutationBlocked",
        "networkBlocked",
        "connectorsBlocked",
        "evalContentBlocked",
        "activationOnly",
        "commandPolicyEnforced",
    }
    if set(sandbox) != required:
        raise UnsupportedEvidence("sandbox evidence is incomplete")
    if any(type(sandbox[key]) is not bool for key in required):
        raise EvalError("sandbox evidence values must be booleans")
    if sandbox["evalContentBlocked"] is not True:
        return False
    if sandbox["commandPolicyEnforced"] is not True:
        return False
    if assertion_type == "sandbox.noExternalRead":
        return sandbox["externalReadBlocked"] is True
    return all(
        sandbox[key] is True
        for key in (
            "externalMutationBlocked",
            "networkBlocked",
            "connectorsBlocked",
        )
    )


def check_primary_skill(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    return (
        primary_skills(context.events) == [context.case["skill"]]
        and activated_wow_skills(context.case, context.events)
        == [context.case["skill"]]
    )


def check_workspace_unchanged(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    return context.unchanged


def check_diff_non_empty(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    return bool(context.paths)


def check_artifact_changed(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return any(re.search(assertion["pattern"], path) for path in context.paths)


def check_diff_regex(assertion: dict[str, Any], context: AssertionContext) -> bool:
    return re.search(assertion["pattern"], context.diff, re.DOTALL) is not None


def check_cart_capacity_branches(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    expected_limit = 10 if context.case["id"] == "B02-develop-test-first" else 100
    return cart_capacity_oracle(
        Path(context.marker["workspace"]),
        expected_limit=expected_limit,
        oracle_runtime=Path(context.marker["runDir"]) / ORACLE_RUNTIME_DIR,
    )


def review_changed_files(context: AssertionContext) -> tuple[str, set[str]]:
    workspace = Path(context.marker["workspace"])
    fixture = context.marker["fixture"]
    base = fixture.get("baseSha")
    baseline = fixture["baselineCommit"]
    if not isinstance(base, str):
        raise EvalError("reviewed-file assertion requires a base revision")
    merge_base = require_git(workspace, ["merge-base", base, baseline]).strip()
    changed = set(
        require_git(
            workspace, ["diff", "--name-only", merge_base, baseline, "--"]
        ).splitlines()
    )
    return merge_base, changed


def check_reviewed_changed_file(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    _, changed = review_changed_files(context)
    reads = {
        str(event["path"]).removeprefix("workspace/")
        for event in context.events
        if event["type"] == "read" and str(event["path"]).startswith("workspace/")
    }
    return bool(changed & reads)


def check_reviewed_all_changed_files(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    _, changed = review_changed_files(context)
    base = context.marker["fixture"]["baseSha"]
    full_diff_argv = [
        "git",
        "diff",
        "--no-ext-diff",
        "--no-textconv",
        f"{base}...HEAD",
        "--",
    ]
    full_diff_seen = any(
        command_event_matches(event, full_diff_argv, context.marker)
        and event["exitCode"] == 0
        for event in context.events
    )
    if not full_diff_seen:
        return False
    reads = {
        str(event["path"]).removeprefix("workspace/")
        for event in context.events
        if event["type"] == "read" and str(event["path"]).startswith("workspace/")
    }
    return not changed or changed.issubset(reads) or full_diff_seen


def check_trace_pattern(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return check_pattern_event(
        assertion["type"], assertion["pattern"], context.events
    )


def check_command_assertion(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return check_command_exit(assertion, context.events, context.marker)


def check_order_assertion(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return check_trace_order(assertion["events"], context.events, context.marker)


def check_output_regex(assertion: dict[str, Any], context: AssertionContext) -> bool:
    return re.search(
        assertion["pattern"], context.evidence["output"], re.DOTALL
    ) is not None


def check_output_not_regex(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return re.search(
        assertion["pattern"], context.evidence["output"], re.DOTALL
    ) is None


def check_sandbox_assertion(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return check_sandbox(context.evidence, assertion["type"])


def check_process_exit(assertion: dict[str, Any], context: AssertionContext) -> bool:
    return context.evidence["processExitCode"] == assertion["value"]


ASSERTION_CHECKERS = {
    "activation.primarySkill": check_primary_skill,
    "workspace.unchanged": check_workspace_unchanged,
    "diff.nonEmpty": check_diff_non_empty,
    "artifact.changed": check_artifact_changed,
    "diff.regex": check_diff_regex,
    "oracle.cartCapacityBranches": check_cart_capacity_branches,
    "trace.reviewedAllChangedFiles": check_reviewed_all_changed_files,
    "trace.reviewedChangedFile": check_reviewed_changed_file,
    "trace.read": check_trace_pattern,
    "trace.notRead": check_trace_pattern,
    "trace.write": check_trace_pattern,
    "trace.notWrite": check_trace_pattern,
    "command.exit": check_command_assertion,
    "trace.order": check_order_assertion,
    "output.regex": check_output_regex,
    "output.notRegex": check_output_not_regex,
    "sandbox.noExternalRead": check_sandbox_assertion,
    "sandbox.noExternalMutation": check_sandbox_assertion,
    "process.exitCode": check_process_exit,
}


def assertion_passes(
    assertion: dict[str, Any],
    case: dict[str, Any],
    evidence: dict[str, Any],
    events: list[dict[str, Any]],
    paths: list[str],
    diff: str,
    unchanged: bool,
    marker: dict[str, Any],
) -> bool:
    assertion_type = assertion["type"]
    checker = ASSERTION_CHECKERS.get(assertion_type)
    if checker is None:
        raise EvalError(f"runner does not implement assertion {assertion_type}")
    context = AssertionContext(
        case, evidence, events, paths, diff, unchanged, marker
    )
    return checker(assertion, context)


def verify_write_allow(case: dict[str, Any], paths: list[str]) -> list[str]:
    patterns = case["fixture"]["writeAllow"] + TOOL_WRITE_ALLOW
    return [
        path
        for path in paths
        if any(
            fnmatch.fnmatchcase(path, pattern)
            for pattern in TOOL_CONTROL_WRITE_DENY
        )
        or not any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)
    ]


def verify_trace_write_allow(
    case: dict[str, Any], events: list[dict[str, Any]]
) -> list[str]:
    patterns = case["fixture"]["writeAllow"] + TOOL_WRITE_ALLOW
    forbidden: list[str] = []
    for event in events:
        if event["type"] != "write":
            continue
        path = str(event["path"])
        if not path.startswith("workspace/"):
            forbidden.append(path)
            continue
        relative = path.removeprefix("workspace/")
        if any(
            fnmatch.fnmatchcase(relative, pattern)
            for pattern in TOOL_CONTROL_WRITE_DENY
        ) or not any(fnmatch.fnmatchcase(relative, pattern) for pattern in patterns):
            forbidden.append(relative)
    return forbidden


def verify_activation_case(
    case: dict[str, Any], evidence: dict[str, Any], events: list[dict[str, Any]]
) -> list[str]:
    expected = case["expectedSkills"]
    actual = activated_wow_skills(case, events)
    primary = [
        skill
        for skill in primary_skills(events)
        if skill in set(case["__known_skills__"])
    ]
    failures: list[str] = []
    if actual != expected:
        failures.append(f"Wow skill activation mismatch: expected={expected}, actual={actual}")
    if primary != expected:
        failures.append(f"primary Wow skill mismatch: expected={expected}, actual={primary}")
    if any(event["type"] != "activation" for event in events):
        failures.append("activation-only run continued into tool execution")
    sandbox = evidence["sandbox"]
    if sandbox.get("activationOnly") is not True:
        failures.append("adapter did not enforce activation-only termination")
    if not check_sandbox(evidence, "sandbox.noExternalRead"):
        failures.append("activation run did not block external reads")
    if not check_sandbox(evidence, "sandbox.noExternalMutation"):
        failures.append("activation run did not block external mutations")
    if evidence["processExitCode"] != 0:
        failures.append(
            f"adapter process exited with {evidence['processExitCode']}"
        )
    return failures


def verify_behavior_case(
    case: dict[str, Any],
    marker: dict[str, Any],
    evidence: dict[str, Any],
    events: list[dict[str, Any]],
) -> list[str]:
    workspace = Path(marker["workspace"])
    baseline = marker["fixture"]["baselineCommit"]
    manifest_path = Path(marker["runDir"]) / BASELINE_MANIFEST_FILE
    baseline_files = read_object(manifest_path, "baseline manifest")
    current_files = tree_manifest(workspace)
    all_paths = manifest_changed_paths(baseline_files, current_files)
    paths = [
        path
        for path in all_paths
        if not any(fnmatch.fnmatchcase(path, pattern) for pattern in TOOL_WRITE_ALLOW)
    ]
    new_paths = [
        path
        for path in paths
        if path not in baseline_files and current_files[path].get("kind") == "file"
    ]
    diff = workspace_diff(workspace, baseline, new_paths)
    unchanged = not paths and workspace_head(workspace) == baseline
    failures: list[str] = []
    forbidden = verify_write_allow(case, all_paths)
    if forbidden:
        failures.append(f"changed paths escaped writeAllow: {forbidden}")
    trace_forbidden = verify_trace_write_allow(case, events)
    if trace_forbidden:
        failures.append(f"write trace escaped writeAllow: {trace_forbidden}")
    for index, assertion in enumerate(case["assertions"]):
        if not assertion_passes(
            assertion, case, evidence, events, paths, diff, unchanged, marker
        ):
            failures.append(f"assertion[{index}] failed: {assertion['type']}")
    return failures
