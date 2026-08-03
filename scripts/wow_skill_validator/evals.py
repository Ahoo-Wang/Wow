"""Wow Skill validator evals primitives."""
from __future__ import annotations

from collections.abc import Iterable
from pathlib import Path
from typing import Any
import json
import re
import shlex

from .core import (
    ACTIVATION_KEYS,
    ACTIVATION_SCHEMA_VERSION,
    ASSERTION_TYPES,
    BEHAVIOR_KEYS,
    BEHAVIOR_MODES,
    BEHAVIOR_SCHEMA_VERSION,
    BOOLEAN_ASSERTIONS,
    FIXTURE_KINDS,
    FIXTURE_OPTIONAL_KEYS,
    FIXTURE_REQUIRED_KEYS,
    INTEGER_ASSERTIONS,
    PATTERN_ASSERTIONS,
    Validation,
    lexical_relative_path,
    require_string,
    resolve_contained_path,
    validate_exact_keys,
    validate_no_symlinks,
)

__all__ = [
    "read_jsonl",
    "validate_tags",
    "validate_activation_case",
    "validate_prompt_does_not_leak",
    "validate_activation_cases",
    "validate_patch_path",
    "validate_patch_file",
    "validate_write_allow",
    "validate_fixture_location",
    "validate_fixture_setup",
    "validate_fixture",
    "validate_regex",
    "validate_command_exit",
    "validate_command_argv",
    "command_uses_shell_wrapper",
    "validate_order_step",
    "validate_trace_order",
    "validate_assertion",
    "order_proven_red_green_argv",
    "validate_common_behavior_policy",
    "validate_read_only_policy",
    "validate_mutating_policy",
    "validate_behavior_policy",
    "collect_behavior_assertions",
    "validate_zero_process_exit",
    "validate_behavior_case",
    "validate_behavior_cases",
    "validate_unique_eval_ids",
    "validate_evals",
    "load_skill_evals",
]


def read_jsonl(path: Path, validation: Validation) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        validation.error(f"{path}: cannot read: {error}")
        return cases

    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        source = f"{path}:{line_number}"
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            validation.error(f"{source}: invalid JSON: {error}")
            continue
        if not isinstance(value, dict):
            validation.error(f"{source}: expected a JSON object")
            continue
        value["__source__"] = source
        cases.append(value)
    return cases


def validate_tags(
    case: dict[str, Any], source: str, validation: Validation
) -> list[str]:
    tags = case.get("tags")
    if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
        validation.error(f"{source}: tags must be a string array")
        return []
    return tags


def validate_activation_case(
    case: dict[str, Any], skill_names: set[str], validation: Validation
) -> tuple[set[str], bool, bool]:
    source = str(case["__source__"])
    validate_exact_keys(case, ACTIVATION_KEYS, source, validation)
    if (
        type(case.get("schemaVersion")) is not int
        or case.get("schemaVersion") != ACTIVATION_SCHEMA_VERSION
    ):
        validation.error(f"{source}: schemaVersion must be 1")
    prompt = require_string(case, "prompt", source, validation)
    expected = case.get("expectedSkills")
    if not isinstance(expected, list) or any(
        not isinstance(name, str) for name in expected
    ):
        validation.error(f"{source}: expectedSkills must be a string array")
        expected = []
    expected_set = set(expected)
    if expected_set - skill_names:
        validation.error(
            f"{source}: unknown expected skills: {sorted(expected_set - skill_names)}"
        )
    if len(expected) != len(expected_set):
        validation.error(f"{source}: expectedSkills contains duplicates")
    if len(expected) > 1:
        validation.error(
            f"{source}: expectedSkills must contain at most one Primary Skill"
        )
    tags = validate_tags(case, source, validation)
    if prompt:
        validate_prompt_does_not_leak(prompt, source, skill_names, validation)
        if "language-en" in tags and not prompt.isascii():
            validation.error(
                f"{source}: language-en activation prompt must contain only ASCII text"
            )
    return expected_set, not expected, "conflict" in tags


def validate_prompt_does_not_leak(
    prompt: str, source: str, skill_names: set[str], validation: Validation
) -> None:
    if "$" in prompt:
        validation.error(f"{source}: eval prompt must not name a $skill")
    leaked = [name for name in skill_names if name.lower() in prompt.lower()]
    if leaked:
        validation.error(f"{source}: eval prompt leaks skill names: {sorted(leaked)}")


def validate_activation_cases(
    cases: Iterable[dict[str, Any]], skill_names: set[str], validation: Validation
) -> None:
    coverage: set[str] = set()
    negative_count = 0
    conflict_count = 0
    english_count = 0
    scenario_tags: set[str] = set()
    total = 0
    for case in cases:
        total += 1
        expected, negative, conflict = validate_activation_case(
            case, skill_names, validation
        )
        coverage.update(expected)
        negative_count += int(negative)
        conflict_count += int(conflict)
        tags = case.get("tags")
        if isinstance(tags, list):
            scenario_tags.update(tag for tag in tags if isinstance(tag, str))
            english_count += int("language-en" in tags)
    if total < 24:
        validation.error(f"activation evals: expected at least 24 cases, found {total}")
    if negative_count < 5:
        validation.error(
            f"activation evals: expected at least 5 negative cases, found {negative_count}"
        )
    if conflict_count < 10:
        validation.error(
            f"activation evals: expected at least 10 conflict cases, found {conflict_count}"
        )
    if english_count < 5:
        validation.error(
            f"activation evals: expected at least 5 English cases, found {english_count}"
        )
    required_scenarios = {
        "review-data-cutover",
        "debug-data-cutover",
        "breaking-no-data",
    }
    missing_scenarios = required_scenarios - scenario_tags
    if missing_scenarios:
        validation.error(
            "activation evals: missing boundary scenarios "
            f"{sorted(missing_scenarios)}"
        )
    missing = skill_names - coverage
    if missing:
        validation.error(
            f"activation evals: no positive expected activation for {sorted(missing)}"
        )


def validate_patch_path(raw: str, source: Path, validation: Validation) -> None:
    if not raw.startswith(("a/", "b/")):
        validation.error(f"{source}: patch path must start with a/ or b/: {raw!r}")
        return
    relative = lexical_relative_path(raw[2:])
    if relative is None or relative.parts[0] == ".git":
        validation.error(f"{source}: unsafe patch path {raw!r}")


def validate_patch_file(path: Path, validation: Validation) -> None:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        validation.error(f"{path}: cannot read setup patch: {error}")
        return
    headers = 0
    hunks = 0
    for line in lines:
        if line.startswith("diff --git "):
            try:
                tokens = shlex.split(line)
            except ValueError as error:
                validation.error(f"{path}: invalid patch header: {error}")
                continue
            if len(tokens) != 4:
                validation.error(f"{path}: invalid diff --git header")
                continue
            validate_patch_path(tokens[2], path, validation)
            validate_patch_path(tokens[3], path, validation)
            headers += 1
        elif line.startswith("@@ "):
            hunks += 1
        elif line in {"new file mode 120000", "new mode 120000"}:
            validation.error(f"{path}: setup patch must not create symlinks")
    if headers == 0 or hunks == 0:
        validation.error(f"{path}: setup must be a unified git patch with a hunk")


def validate_write_allow(
    value: Any, mode: str | None, source: str, validation: Validation
) -> list[str]:
    if not isinstance(value, list) or any(
        not isinstance(pattern, str) or not pattern for pattern in value
    ):
        validation.error(f"{source}: fixture.writeAllow must be a string array")
        return []
    if mode == "read-only" and value:
        validation.error(f"{source}: read-only fixture.writeAllow must be empty")
    if mode == "mutating" and not value:
        validation.error(f"{source}: mutating fixture.writeAllow must be non-empty")
    for pattern in value:
        if Path(pattern).is_absolute() or ".." in Path(pattern).parts:
            validation.error(f"{source}: unsafe writeAllow pattern {pattern!r}")
    return value


def validate_fixture_location(
    fixture: dict[str, Any], evals_dir: Path, source: str, validation: Validation
) -> None:
    kind = fixture.get("kind")
    repository = fixture.get("repository")
    revision = fixture.get("revision")
    fixtures_root = evals_dir / "fixtures"
    if isinstance(kind, str) and kind in {
        "isolated-git-worktree",
        "isolated-git-clone",
    }:
        if repository != ".":
            validation.error(f"{source}: isolated Git repository must be '.'")
        if revision != "EVAL_SUBJECT":
            validation.error(
                f"{source}: isolated Git revision must be EVAL_SUBJECT"
            )
    elif kind == "copied-directory":
        if revision != "CONTENT_SHA256":
            validation.error(
                f"{source}: copied-directory revision must be CONTENT_SHA256"
            )
        if not isinstance(repository, str) or not repository:
            validation.error(
                f"{source}: copied-directory repository must be a non-empty string"
            )
            return
        resolved = resolve_contained_path(
            fixtures_root,
            repository,
            f"{source}:fixture.repository",
            validation,
            expected="directory",
        )
        if resolved is not None:
            validate_no_symlinks(resolved, source, validation)


def validate_fixture_setup(
    fixture: dict[str, Any], evals_dir: Path, source: str, validation: Validation
) -> None:
    setup = fixture.get("setup")
    if not isinstance(setup, str) or not setup:
        validation.error(f"{source}: fixture.setup must be non-empty")
        return
    if setup == "none":
        return
    if fixture.get("kind") == "copied-directory":
        validation.error(f"{source}: copied-directory fixture.setup must be none")
        return
    setup_path = resolve_contained_path(
        evals_dir / "fixtures",
        setup,
        f"{source}:fixture.setup",
        validation,
        expected="file",
    )
    if setup_path is None:
        return
    if setup_path.suffix != ".patch":
        validation.error(f"{source}: fixture.setup must be none or a .patch file")
        return
    validate_patch_file(setup_path, validation)


def validate_fixture(
    fixture: Any,
    case_id: str | None,
    skill: str | None,
    mode: str | None,
    source: str,
    validation: Validation,
) -> None:
    if not isinstance(fixture, dict):
        validation.error(f"{source}: fixture must be an object")
        return
    actual = set(fixture)
    allowed = FIXTURE_REQUIRED_KEYS | FIXTURE_OPTIONAL_KEYS
    if actual - allowed:
        validation.error(f"{source}: fixture has unknown keys {sorted(actual - allowed)}")
    if FIXTURE_REQUIRED_KEYS - actual:
        validation.error(
            f"{source}: fixture missing keys {sorted(FIXTURE_REQUIRED_KEYS - actual)}"
        )
    fixture_id = fixture.get("fixtureId")
    if fixture_id != case_id:
        validation.error(f"{source}: fixture.fixtureId must equal the case id")
    kind = fixture.get("kind")
    if not isinstance(kind, str) or kind not in FIXTURE_KINDS:
        validation.error(f"{source}: fixture.kind must be one of {sorted(FIXTURE_KINDS)}")
    if fixture.get("initialState") != "clean":
        validation.error(f"{source}: fixture.initialState must be clean")
    evals_dir = Path(source.rsplit(":", 1)[0]).parent
    validate_fixture_location(fixture, evals_dir, source, validation)
    validate_fixture_setup(fixture, evals_dir, source, validation)
    validate_write_allow(fixture.get("writeAllow"), mode, source, validation)
    base_revision = fixture.get("baseRevision")
    if base_revision is not None and (
        not isinstance(base_revision, str)
        or base_revision not in {"EVAL_BASE", "EVAL_SUBJECT"}
    ):
        validation.error(
            f"{source}: fixture.baseRevision must be EVAL_BASE or EVAL_SUBJECT"
        )
    if skill == "wow-review" and base_revision is None:
        validation.error(f"{source}: wow-review fixtures require a baseRevision")


def validate_regex(pattern: Any, source: str, validation: Validation) -> None:
    if not isinstance(pattern, str) or not pattern:
        validation.error(f"{source}: pattern must be a non-empty string")
        return
    try:
        re.compile(pattern)
    except re.error as error:
        validation.error(f"{source}: invalid regex {pattern!r}: {error}")


def validate_command_exit(
    assertion: dict[str, Any], source: str, validation: Validation
) -> None:
    validate_exact_keys(assertion, {"type", "argv", "exitCode"}, source, validation)
    validate_command_argv(assertion.get("argv"), source, validation)
    exit_code = assertion.get("exitCode")
    if type(exit_code) is not int and exit_code != "nonzero":
        validation.error(f"{source}: exitCode must be an integer or 'nonzero'")


def validate_command_argv(value: Any, source: str, validation: Validation) -> None:
    if not isinstance(value, list) or not value or any(
        not isinstance(item, str) or not item for item in value
    ):
        validation.error(f"{source}: argv must be a non-empty string array")
        return
    if command_uses_shell_wrapper(value):
        validation.error(f"{source}: shell-wrapper command evidence is forbidden")


def command_uses_shell_wrapper(argv: list[str]) -> bool:
    shells = {"bash", "sh", "zsh", "cmd", "cmd.exe", "powershell", "pwsh"}
    executable_index = 0
    executable = Path(argv[0]).name.lower()
    if executable == "env":
        index = 1
        while index < len(argv):
            argument = argv[index]
            if argument == "--":
                index += 1
                break
            if argument == "-S" or argument == "--split-string":
                try:
                    split = shlex.split(" ".join(argv[index + 1 :]))
                except ValueError:
                    return True
                return any(Path(token).name.lower() in shells for token in split)
            if argument in {"-u", "--unset", "-C", "--chdir"}:
                index += 2
                continue
            if argument.startswith("-") or "=" in argument:
                index += 1
                continue
            break
        if index >= len(argv):
            return False
        executable_index = index
        executable = Path(argv[index]).name.lower()
    if executable not in shells:
        return False
    return any(
        argument.lower() in {"/c", "/k", "--command"}
        or (argument.startswith("-") and "c" in argument[1:].lower())
        for argument in argv[executable_index + 1 :]
    )


def validate_order_step(
    step: Any, source: str, validation: Validation
) -> dict[str, Any] | None:
    if not isinstance(step, dict):
        validation.error(f"{source}: order step must be an object")
        return None
    event = step.get("event")
    allowed = {"event", "pattern"}
    if event == "command":
        allowed = {"event", "argv", "exitCode"}
    validate_exact_keys(step, allowed, source, validation)
    if not isinstance(event, str) or event not in {"read", "write", "command"}:
        validation.error(f"{source}: event must be read, write, or command")
    if event == "command":
        validate_command_argv(step.get("argv"), source, validation)
        exit_code = step.get("exitCode")
        if type(exit_code) is not int and exit_code != "nonzero":
            validation.error(f"{source}: command exitCode must be integer or nonzero")
    else:
        validate_regex(step.get("pattern"), source, validation)
    return step


def validate_trace_order(
    assertion: dict[str, Any], source: str, validation: Validation
) -> list[dict[str, Any]]:
    validate_exact_keys(assertion, {"type", "events"}, source, validation)
    events = assertion.get("events")
    if not isinstance(events, list) or len(events) < 2:
        validation.error(f"{source}: trace.order requires at least two events")
        return []
    valid: list[dict[str, Any]] = []
    for index, step in enumerate(events):
        checked = validate_order_step(step, f"{source}:events[{index}]", validation)
        if checked is not None:
            valid.append(checked)
    return valid


def validate_assertion(
    assertion: Any, source: str, validation: Validation
) -> tuple[str | None, list[dict[str, Any]]]:
    if not isinstance(assertion, dict):
        validation.error(f"{source}: expected an object")
        return None, []
    assertion_type = assertion.get("type")
    if not isinstance(assertion_type, str) or assertion_type not in ASSERTION_TYPES:
        validation.error(f"{source}: unknown assertion type {assertion_type!r}")
        return None, []
    if assertion_type in BOOLEAN_ASSERTIONS:
        validate_exact_keys(assertion, {"type", "value"}, source, validation)
        if assertion.get("value") is not True:
            validation.error(f"{source}: {assertion_type} requires value true")
    elif assertion_type in PATTERN_ASSERTIONS:
        validate_exact_keys(assertion, {"type", "pattern"}, source, validation)
        validate_regex(assertion.get("pattern"), source, validation)
    elif assertion_type in INTEGER_ASSERTIONS:
        validate_exact_keys(assertion, {"type", "value"}, source, validation)
        if type(assertion.get("value")) is not int:
            validation.error(f"{source}: {assertion_type} requires an integer")
    elif assertion_type == "command.exit":
        validate_command_exit(assertion, source, validation)
    else:
        return assertion_type, validate_trace_order(assertion, source, validation)
    return assertion_type, []


def order_proven_red_green_argv(
    events: list[dict[str, Any]],
) -> set[tuple[str, ...]]:
    proven: set[tuple[str, ...]] = set()
    for failure_index, failure in enumerate(events):
        argv = failure.get("argv")
        if (
            failure.get("event") != "command"
            or failure.get("exitCode") != "nonzero"
            or not isinstance(argv, list)
            or any(not isinstance(argument, str) for argument in argv)
        ):
            continue
        for write_index in range(failure_index + 1, len(events)):
            if events[write_index].get("event") != "write":
                continue
            if any(
                success.get("event") == "command"
                and success.get("exitCode") == 0
                and success.get("argv") == argv
                for success in events[write_index + 1 :]
            ):
                proven.add(tuple(argv))
                break
    return proven


def validate_common_behavior_policy(
    mode: str | None,
    fixture: Any,
    assertion_types: set[str],
    source: str,
    validation: Validation,
) -> None:
    required = {"activation.primarySkill", "process.exitCode"}
    if not required.issubset(assertion_types):
        validation.error(f"{source}: assertions must include {sorted(required)}")
    if "sandbox.noExternalMutation" not in assertion_types:
        validation.error(f"{source}: behavior case requires sandbox.noExternalMutation")
    requires_external_read_block = mode == "read-only" or (
        isinstance(fixture, dict) and fixture.get("kind") == "copied-directory"
    )
    if requires_external_read_block and "sandbox.noExternalRead" not in assertion_types:
        validation.error(f"{source}: hermetic case requires sandbox.noExternalRead")


def validate_read_only_policy(
    assertion_types: set[str], source: str, validation: Validation
) -> None:
    if "workspace.unchanged" not in assertion_types:
        validation.error(f"{source}: read-only case requires workspace.unchanged")
    forbidden = {"diff.nonEmpty", "artifact.changed", "trace.write"}
    declared = forbidden & assertion_types
    if declared:
        validation.error(
            f"{source}: read-only case declares mutating assertions {sorted(declared)}"
        )


def validate_mutating_policy(
    assertions: list[dict[str, Any]],
    assertion_types: set[str],
    orders: list[list[dict[str, Any]]],
    source: str,
    validation: Validation,
) -> None:
    mutating_required = {"diff.nonEmpty", "artifact.changed", "command.exit", "trace.order"}
    if not mutating_required.issubset(assertion_types):
        validation.error(
            f"{source}: mutating case requires {sorted(mutating_required)}"
        )
    proven_commands: set[tuple[str, ...]] = set()
    for events in orders:
        proven_commands.update(order_proven_red_green_argv(events))
    if not proven_commands:
        validation.error(
            f"{source}: mutating case requires the same command argv for "
            "RED -> write -> GREEN trace.order"
        )
    successful_commands = {
        tuple(assertion["argv"])
        for assertion in assertions
        if isinstance(assertion, dict)
        and assertion.get("type") == "command.exit"
        and assertion.get("exitCode") == 0
        and isinstance(assertion.get("argv"), list)
        and all(isinstance(argument, str) for argument in assertion["argv"])
    }
    if not successful_commands:
        validation.error(f"{source}: mutating case requires a successful command.exit")
    elif proven_commands and not proven_commands & successful_commands:
        validation.error(
            f"{source}: successful command.exit must match the GREEN argv in "
            "RED -> write -> GREEN trace.order"
        )
    if "workspace.unchanged" in assertion_types:
        validation.error(f"{source}: mutating case must not require workspace.unchanged")


def validate_behavior_policy(
    mode: str | None,
    fixture: Any,
    assertions: list[dict[str, Any]],
    assertion_types: list[str],
    orders: list[list[dict[str, Any]]],
    source: str,
    validation: Validation,
) -> None:
    assertion_type_set = set(assertion_types)
    validate_common_behavior_policy(
        mode, fixture, assertion_type_set, source, validation
    )
    if mode == "read-only":
        validate_read_only_policy(assertion_type_set, source, validation)
    elif mode == "mutating":
        validate_mutating_policy(
            assertions, assertion_type_set, orders, source, validation
        )


def collect_behavior_assertions(
    assertions: list[Any], source: str, validation: Validation
) -> tuple[list[str], list[list[dict[str, Any]]]]:
    assertion_types: list[str] = []
    orders: list[list[dict[str, Any]]] = []
    for index, assertion in enumerate(assertions):
        assertion_type, order = validate_assertion(
            assertion, f"{source}:assertions[{index}]", validation
        )
        if assertion_type:
            assertion_types.append(assertion_type)
        if order:
            orders.append(order)
    return assertion_types, orders


def validate_zero_process_exit(
    assertions: list[Any], assertion_types: list[str], source: str, validation: Validation
) -> None:
    if "process.exitCode" not in assertion_types:
        return
    invalid = any(
        isinstance(assertion, dict)
        and assertion.get("type") == "process.exitCode"
        and assertion.get("value") != 0
        for assertion in assertions
    )
    if invalid:
        validation.error(f"{source}: process.exitCode must require zero")


def validate_behavior_case(
    case: dict[str, Any], skill_names: set[str], validation: Validation
) -> str | None:
    source = str(case["__source__"])
    validate_exact_keys(case, BEHAVIOR_KEYS, source, validation)
    if (
        type(case.get("schemaVersion")) is not int
        or case.get("schemaVersion") != BEHAVIOR_SCHEMA_VERSION
    ):
        validation.error(f"{source}: behavior schemaVersion must be 2")
    case_id = require_string(case, "id", source, validation)
    prompt = require_string(case, "prompt", source, validation)
    skill = require_string(case, "skill", source, validation)
    if skill and skill not in skill_names:
        validation.error(f"{source}: unknown behavior skill {skill!r}")
    mode = case.get("mode")
    if not isinstance(mode, str) or mode not in BEHAVIOR_MODES:
        validation.error(f"{source}: mode must be one of {sorted(BEHAVIOR_MODES)}")
        mode = None
    if prompt:
        validate_prompt_does_not_leak(prompt, source, skill_names, validation)
    validate_tags(case, source, validation)
    fixture = case.get("fixture")
    validate_fixture(fixture, case_id, skill, mode, source, validation)
    assertions = case.get("assertions")
    if not isinstance(assertions, list) or not assertions:
        validation.error(f"{source}: assertions must be a non-empty array")
        return skill
    assertion_types, orders = collect_behavior_assertions(
        assertions, source, validation
    )
    validate_behavior_policy(
        mode, fixture, assertions, assertion_types, orders, source, validation
    )
    validate_zero_process_exit(assertions, assertion_types, source, validation)
    return skill


def validate_behavior_cases(
    cases: Iterable[dict[str, Any]], skill_names: set[str], validation: Validation
) -> None:
    coverage: set[str] = set()
    fixture_ids: set[str] = set()
    for case in cases:
        skill = validate_behavior_case(case, skill_names, validation)
        if skill:
            coverage.add(skill)
        case_id = case.get("id")
        if isinstance(case_id, str):
            if case_id in fixture_ids:
                validation.error(f"{case['__source__']}: duplicate fixtureId {case_id!r}")
            fixture_ids.add(case_id)
    missing = skill_names - coverage
    if missing:
        validation.error(f"behavior evals: no cases for {sorted(missing)}")


def validate_unique_eval_ids(
    cases: Iterable[dict[str, Any]],
    validation: Validation,
    seen: set[str] | None = None,
) -> set[str]:
    """Reject duplicate case ids across files and suites."""
    known_ids = seen if seen is not None else set()
    for case in cases:
        source = str(case["__source__"])
        case_id = require_string(case, "id", source, validation)
        if case_id:
            if case_id in known_ids:
                validation.error(f"{source}: duplicate eval id {case_id!r}")
            known_ids.add(case_id)
    return known_ids


def validate_evals(
    skill_dirs: list[Path], skill_names: set[str], validation: Validation
) -> tuple[int, int]:
    activation_cases: list[dict[str, Any]] = []
    behavior_cases: list[dict[str, Any]] = []
    ids: set[str] = set()
    for skill_dir in skill_dirs:
        current_activation, current_behavior = load_skill_evals(skill_dir, validation)
        validate_unique_eval_ids(
            current_activation + current_behavior, validation, ids
        )
        activation_cases.extend(current_activation)
        behavior_cases.extend(current_behavior)
    validate_activation_cases(activation_cases, skill_names, validation)
    validate_behavior_cases(behavior_cases, skill_names, validation)
    return len(activation_cases), len(behavior_cases)


def load_skill_evals(
    skill_dir: Path, validation: Validation
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    evals_dir = skill_dir / "evals"
    activation_path = evals_dir / "activation.jsonl"
    behavior_path = evals_dir / "behavior.jsonl"
    if not activation_path.is_file() or not behavior_path.is_file():
        validation.error(
            f"{skill_dir}: expected evals/activation.jsonl and evals/behavior.jsonl"
        )
        return [], []
    return read_jsonl(activation_path, validation), read_jsonl(
        behavior_path, validation
    )
