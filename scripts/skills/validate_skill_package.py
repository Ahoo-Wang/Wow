#!/usr/bin/env python3
import argparse
import fnmatch
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse


SKILL_NAMES = {
    "wow",
    "wow-code-review",
    "wow-debugging",
    "wow-development-workflow",
}
CHECK_GROUPS = ("must_contain", "must_not_contain")
AGENT_INTERFACE_FIELDS = ("display_name", "short_description", "default_prompt")
CHECK_SCOPES = {"all", "code"}
ROUTING_MODES = {"direct", "mixed", "negative"}
PLUGIN_INTERFACE_STRING_FIELDS = {
    "displayName",
    "display_name",
    "shortDescription",
    "short_description",
    "longDescription",
    "long_description",
    "developerName",
    "developer_name",
    "category",
    "defaultPrompt",
    "default_prompt",
    "brandColor",
    "brand_color",
}
PLUGIN_STRING_FIELDS = ("version", "homepage", "repository", "license")
PLUGIN_AUTHOR_STRING_FIELDS = ("name", "email", "url")
SEMVER_RE = re.compile(
    r"^(0|[1-9]\d*)\."
    r"(0|[1-9]\d*)\."
    r"(0|[1-9]\d*)"
    r"(?:-(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\."
    r"(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)
HEX_COLOR_RE = re.compile(r"^#[0-9A-F]{6}$", re.IGNORECASE)


class ValidationError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def require_string(value, path: str) -> str:
    require(isinstance(value, str) and value.strip(), f"{path} must be a non-empty string")
    return value


def is_kebab_case(value: str) -> bool:
    return bool(re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", value))


def is_https_url(value: str) -> bool:
    parsed = urlparse(value)
    return parsed.scheme == "https" and bool(parsed.netloc)


def parse_quoted_yaml_scalar(raw_value: str, path: str) -> str:
    try:
        if raw_value.startswith("\""):
            value, end = json.JSONDecoder().raw_decode(raw_value)
            require(isinstance(value, str), f"{path} must be a quoted single-line string")
            suffix = raw_value[end:]
        elif raw_value.startswith("'"):
            match = re.fullmatch(r"'((?:[^']|'')*)'((?:[ \t]*|[ \t]+#.*))", raw_value)
            require(match is not None, f"{path} must be a quoted single-line string")
            value = match.group(1).replace("''", "'")
            suffix = match.group(2)
        else:
            raise ValueError("unquoted scalar")
        require(
            re.fullmatch(r"(?:[ \t]*|[ \t]+#.*)", suffix) is not None,
            f"{path} contains unsupported trailing YAML content",
        )
        return value
    except (json.JSONDecodeError, ValueError) as error:
        raise ValidationError(f"{path} must be a quoted single-line string") from error


def load_agent_metadata(path: Path) -> dict:
    """Load the package-supported, generated agents/openai.yaml subset."""
    interface = {}
    seen_interface = False
    for line_number, line in enumerate(path.read_text().splitlines(), start=1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line == "interface:":
            require(
                not seen_interface,
                f"{path}:{line_number} duplicate interface mapping",
            )
            seen_interface = True
            continue
        require(
            seen_interface,
            f"{path}:{line_number} unsupported or invalid openai.yaml content",
        )
        match = re.fullmatch(r"  ([a-z][a-z0-9_]*):\s*(.+)", line)
        require(
            match is not None and match.group(1) in AGENT_INTERFACE_FIELDS,
            f"{path}:{line_number} unsupported or invalid openai.yaml content",
        )
        key, raw_value = match.groups()
        require(key not in interface, f"{path}:{line_number} duplicate interface.{key}")
        value = parse_quoted_yaml_scalar(
            raw_value,
            f"{path}:{line_number} interface.{key}",
        )
        interface[key] = value
    require(seen_interface, f"{path} must define interface")
    return {"interface": interface}


def validate_agent_metadata(skill_name: str, document: dict) -> None:
    require(isinstance(document, dict), f"{skill_name}/agents/openai.yaml must be a mapping")
    interface = document.get("interface")
    require(isinstance(interface, dict), f"{skill_name}/agents/openai.yaml interface must be a mapping")
    require_string(interface.get("display_name"), f"{skill_name}.interface.display_name")
    short_description = require_string(
        interface.get("short_description"),
        f"{skill_name}.interface.short_description",
    )
    require(
        25 <= len(short_description) <= 64,
        f"{skill_name}.interface.short_description must contain 25-64 characters",
    )
    default_prompt = require_string(
        interface.get("default_prompt"),
        f"{skill_name}.interface.default_prompt",
    )
    require(
        re.search(
            rf"(?<![A-Za-z0-9_$-])\${re.escape(skill_name)}(?![A-Za-z0-9_-])",
            default_prompt,
        )
        is not None,
        f"{skill_name}.interface.default_prompt must mention ${skill_name}",
    )


def validate_plugins(document: dict) -> None:
    require(isinstance(document, dict), "plugins.json must be an object")
    require(
        type(document.get("schemaVersion")) is int and document["schemaVersion"] == 1,
        "plugins.json schemaVersion must be integer 1",
    )
    plugins = document.get("plugins")
    require(isinstance(plugins, list) and plugins, "plugins.json plugins must be non-empty")
    names = []
    for index, plugin in enumerate(plugins):
        path = f"plugins[{index}]"
        require(isinstance(plugin, dict), f"{path} must be an object")
        name = require_string(plugin.get("name"), f"{path}.name")
        require(is_kebab_case(name), f"{path}.name must be kebab-case")
        names.append(name)
        require_string(plugin.get("description"), f"{path}.description")
        for field in PLUGIN_STRING_FIELDS:
            if field in plugin:
                require_string(plugin[field], f"{path}.{field}")
        if "version" in plugin:
            require(
                SEMVER_RE.fullmatch(plugin["version"]) is not None,
                f"{path}.version must be strict semver",
            )
        if "homepage" in plugin:
            require(is_https_url(plugin["homepage"]), f"{path}.homepage must be an https URL")
        if "author" in plugin:
            author = plugin["author"]
            require(isinstance(author, dict), f"{path}.author must be an object")
            require(
                set(author) <= set(PLUGIN_AUTHOR_STRING_FIELDS),
                f"{path}.author contains unsupported fields",
            )
            require_string(author.get("name"), f"{path}.author.name")
            for field in PLUGIN_AUTHOR_STRING_FIELDS[1:]:
                if field in author:
                    require_string(author[field], f"{path}.author.{field}")
            if "url" in author:
                require(is_https_url(author["url"]), f"{path}.author.url must be an https URL")
        skills = plugin.get("skills")
        if isinstance(skills, list):
            require(
                skills and all(isinstance(item, str) and item for item in skills),
                f"{path}.skills must be a non-empty string array",
            )
            require(len(skills) == len(set(skills)), f"{path}.skills must be unique")
        else:
            require(isinstance(skills, dict), f"{path}.skills must be an array or object")
            include = skills.get("include", ["*"])
            exclude = skills.get("exclude", ["*-workspace"])
            require(
                isinstance(include, list)
                and include
                and all(isinstance(item, str) and item for item in include),
                f"{path}.skills.include must be a non-empty string array",
            )
            require(
                isinstance(exclude, list)
                and all(isinstance(item, str) and item for item in exclude),
                f"{path}.skills.exclude must be a string array",
            )

        keywords = plugin.get("keywords", [])
        require(
            isinstance(keywords, list) and all(isinstance(item, str) and item for item in keywords),
            f"{path}.keywords must be a string array",
        )
        if "category" in plugin:
            require_string(plugin["category"], f"{path}.category")

        interface = plugin.get("interface", {})
        require(isinstance(interface, dict), f"{path}.interface must be an object")
        for field in PLUGIN_INTERFACE_STRING_FIELDS:
            if field in interface:
                require_string(interface[field], f"{path}.interface.{field}")
        for field in ("brandColor", "brand_color"):
            if field in interface:
                require(
                    HEX_COLOR_RE.fullmatch(interface[field]) is not None,
                    f"{path}.interface.{field} must use #RRGGBB",
                )
        capabilities = interface.get("capabilities", ["Skills"])
        require(
            isinstance(capabilities, list)
            and capabilities
            and all(isinstance(item, str) and item for item in capabilities),
            f"{path}.interface.capabilities must be a non-empty string array",
        )

        policy = plugin.get("policy", {})
        require(isinstance(policy, dict), f"{path}.policy must be an object")
        if "installation" in policy:
            require_string(policy["installation"], f"{path}.policy.installation")
        if "authentication" in policy:
            require_string(policy["authentication"], f"{path}.policy.authentication")
    require(len(names) == len(set(names)), "plugins.json plugin names must be unique")


def validate_eval_document(document: dict, repo_root: Path) -> None:
    require(isinstance(document, dict), "evals.json must be an object")
    require(document.get("schema_version") == 2, "evals.json schema_version must be 2")
    require(document.get("skill_name") == "wow", "evals.json skill_name must be wow")
    scope = document.get("scope")
    require(isinstance(scope, dict), "evals.json scope must be an object")
    require(scope.get("languages") == ["kotlin"], "evals.json scope.languages must be ['kotlin']")
    evals = document.get("evals")
    require(isinstance(evals, list) and evals, "evals.json evals must be non-empty")

    eval_ids = []
    for index, eval_case in enumerate(evals):
        path = f"evals[{index}]"
        require(isinstance(eval_case, dict), f"{path} must be an object")
        eval_id = require_string(eval_case.get("id"), f"{path}.id")
        require(is_kebab_case(eval_id), f"{path}.id must be kebab-case")
        eval_ids.append(eval_id)
        target_skill = require_string(eval_case.get("target_skill"), f"{path}.target_skill")
        require(target_skill in SKILL_NAMES, f"{path}.target_skill is unknown: {target_skill}")
        require_string(eval_case.get("prompt"), f"{path}.prompt")
        require_string(eval_case.get("expected_output"), f"{path}.expected_output")
        validate_source_refs(eval_case.get("source_refs"), repo_root, path)
        validate_rubric(eval_case.get("rubric"), path)
        validate_output_checks(eval_case.get("output_checks"), path)

    require(len(eval_ids) == len(set(eval_ids)), "duplicate eval id")
    validate_routing_cases(document.get("routing_cases"), evals)


def validate_routing_cases(routing_cases, evals: list[dict]) -> None:
    require(
        isinstance(routing_cases, list) and 3 <= len(routing_cases) <= 8,
        "evals.json routing_cases must contain 3-8 cases",
    )
    eval_targets = {eval_case["id"]: eval_case["target_skill"] for eval_case in evals}
    specialist_names = sorted(SKILL_NAMES - {"wow"}, key=len, reverse=True)
    revealed_skill_pattern = re.compile(
        rf"\$wow(?:[-\w]*)?|SKILL\.md|skills/(?:{'|'.join(map(re.escape, sorted(SKILL_NAMES, key=len, reverse=True)))})"
        rf"|(?<![A-Za-z0-9_-])(?:{'|'.join(map(re.escape, specialist_names))})(?![A-Za-z0-9_-])",
        re.IGNORECASE,
    )
    case_ids = []
    prompts = []
    for index, case in enumerate(routing_cases):
        path = f"routing_cases[{index}]"
        require(isinstance(case, dict), f"{path} must be an object")
        case_id = require_string(case.get("id"), f"{path}.id")
        require(is_kebab_case(case_id), f"{path}.id must be kebab-case")
        case_ids.append(case_id)
        mode = require_string(case.get("mode"), f"{path}.mode")
        require(mode in ROUTING_MODES, f"{path}.mode must be direct, mixed, or negative")
        prompt = require_string(case.get("prompt"), f"{path}.prompt")
        require(
            revealed_skill_pattern.search(prompt) is None,
            f"{path}.prompt must not reveal a skill name or SKILL.md path",
        )
        prompts.append(prompt)
        expectation = case.get("route_expectation")
        require(isinstance(expectation, dict), f"{path}.route_expectation must be an object")
        if mode == "negative":
            require(
                set(expectation) == {"none"} and expectation["none"] is True,
                f"{path} negative case must expect none=true",
            )
        else:
            eval_target = require_string(expectation.get("eval_target"), f"{path}.route_expectation.eval_target")
            require(
                eval_target in eval_targets,
                f"{path}.route_expectation references unknown eval: {eval_target}",
            )
            if mode == "direct":
                require(
                    set(expectation) == {"eval_target"},
                    f"{path} direct case must define only eval_target",
                )
            else:
                require(
                    set(expectation) == {"eval_target", "skill_sequence"},
                    f"{path} mixed case must define eval_target and skill_sequence",
                )
                skill_sequence = expectation["skill_sequence"]
                require(
                    isinstance(skill_sequence, list)
                    and len(skill_sequence) >= 2
                    and all(isinstance(skill, str) and skill in SKILL_NAMES for skill in skill_sequence),
                    f"{path}.route_expectation.skill_sequence must contain at least two known skills",
                )
                require(
                    len(skill_sequence) == len(set(skill_sequence)),
                    f"{path}.route_expectation.skill_sequence must not repeat skills",
                )
                require(
                    skill_sequence[0] == eval_targets[eval_target],
                    f"{path}.route_expectation.skill_sequence must start with the eval target skill",
                )
    require(len(case_ids) == len(set(case_ids)), "routing_cases ids must be unique")
    require(len(prompts) == len(set(prompts)), "routing_cases prompts must be unique")


def validate_source_refs(source_refs, repo_root: Path, path: str) -> None:
    require(
        isinstance(source_refs, list) and source_refs,
        f"{path}.source_refs must be a non-empty array",
    )
    resolved_root = repo_root.resolve()
    for source_ref in source_refs:
        source_ref = require_string(source_ref, f"{path}.source_refs[]")
        require(not Path(source_ref).is_absolute(), f"{path}.source_refs must be relative: {source_ref}")
        candidate = (repo_root / source_ref).resolve()
        require(candidate.is_relative_to(resolved_root), f"{path}.source_refs escapes repo: {source_ref}")
        require(candidate.is_file(), f"{path}.source_refs does not exist: {source_ref}")


def validate_rubric(rubric, path: str) -> None:
    require(isinstance(rubric, list) and rubric, f"{path}.rubric must be a non-empty array")
    rubric_ids = []
    for index, item in enumerate(rubric):
        item_path = f"{path}.rubric[{index}]"
        require(isinstance(item, dict), f"{item_path} must be an object")
        rubric_ids.append(require_string(item.get("id"), f"{item_path}.id"))
        require_string(item.get("criterion"), f"{item_path}.criterion")
    require(len(rubric_ids) == len(set(rubric_ids)), f"{path}.rubric ids must be unique")


def validate_output_checks(output_checks, path: str) -> None:
    require(isinstance(output_checks, dict), f"{path}.output_checks must be an object")
    check_ids = []
    total_checks = 0
    for group in CHECK_GROUPS:
        checks = output_checks.get(group)
        require(isinstance(checks, list), f"{path}.output_checks.{group} must be an array")
        total_checks += len(checks)
        for index, check in enumerate(checks):
            check_path = f"{path}.output_checks.{group}[{index}]"
            require(isinstance(check, dict), f"{check_path} must be an object")
            check_ids.append(require_string(check.get("id"), f"{check_path}.id"))
            pattern_fields = [
                field
                for field in (
                    "any_of",
                    "any_of_regex",
                    "yaml_path_values",
                    "kotlin_handler_discovery",
                )
                if field in check
            ]
            require(
                len(pattern_fields) == 1,
                f"{check_path} must define exactly one supported check type",
            )
            pattern_field = pattern_fields[0]
            patterns = check[pattern_field]
            if pattern_field == "yaml_path_values":
                require(
                    group == "must_contain"
                    and isinstance(patterns, dict)
                    and patterns
                    and all(
                        isinstance(key, str)
                        and key
                        and isinstance(value, str)
                        and value
                        for key, value in patterns.items()
                    ),
                    f"{check_path}.yaml_path_values must be a non-empty string mapping in must_contain",
                )
                require(
                    check.get("scope") == "code",
                    f"{check_path}.yaml_path_values must use code scope",
                )
            elif pattern_field == "kotlin_handler_discovery":
                require(
                    group == "must_contain"
                    and isinstance(patterns, dict)
                    and set(patterns) in (
                        {"annotation", "default_name"},
                        {"annotation", "default_name", "parameter_type"},
                    ),
                    f"{check_path}.kotlin_handler_discovery has an invalid shape",
                )
                annotation = require_string(
                    patterns.get("annotation"),
                    f"{check_path}.kotlin_handler_discovery.annotation",
                )
                default_name = require_string(
                    patterns.get("default_name"),
                    f"{check_path}.kotlin_handler_discovery.default_name",
                )
                require(
                    re.fullmatch(r"[A-Z][A-Za-z0-9_]*", annotation) is not None,
                    f"{check_path}.kotlin_handler_discovery.annotation must be a Kotlin type name",
                )
                require(
                    re.fullmatch(r"[a-zA-Z_][A-Za-z0-9_]*", default_name) is not None,
                    f"{check_path}.kotlin_handler_discovery.default_name must be an identifier",
                )
                if "parameter_type" in patterns:
                    require_string(
                        patterns["parameter_type"],
                        f"{check_path}.kotlin_handler_discovery.parameter_type",
                    )
                require(
                    check.get("scope") == "code",
                    f"{check_path}.kotlin_handler_discovery must use code scope",
                )
            else:
                require(
                    isinstance(patterns, list)
                    and patterns
                    and all(isinstance(value, str) and value for value in patterns),
                    f"{check_path}.{pattern_field} must be a non-empty string array",
                )
                require(
                    len(patterns) == len(set(patterns)),
                    f"{check_path}.{pattern_field} must be unique",
                )
            if pattern_field == "any_of_regex":
                for pattern in patterns:
                    try:
                        re.compile(pattern)
                    except re.error as error:
                        raise ValidationError(
                            f"{check_path}.any_of_regex contains invalid regex: {pattern}"
                        ) from error
            scope = check.get("scope", "all")
            require(scope in CHECK_SCOPES, f"{check_path}.scope must be all or code")
            min_occurrences = check.get("min_occurrences", 1)
            require(
                type(min_occurrences) is int and min_occurrences > 0,
                f"{check_path}.min_occurrences must be a positive integer",
            )
            require(
                (group == "must_contain" and pattern_field != "yaml_path_values")
                or "min_occurrences" not in check,
                f"{check_path}.min_occurrences is only supported for must_contain",
            )
    require(total_checks > 0, f"{path}.output_checks must define at least one check")
    require(len(check_ids) == len(set(check_ids)), f"{path}.output_checks ids must be unique")


def extract_fenced_code(output: str) -> str:
    blocks = re.findall(r"```[^\n]*\n(.*?)```", output, re.DOTALL)
    return "\n".join(blocks)


def check_text(check: dict, output: str, fenced_code: str) -> str:
    return fenced_code if check.get("scope", "all") == "code" else output


def find_balanced_parenthesis_end(code: str, open_index: int) -> int | None:
    depth = 0
    quote = None
    index = open_index
    while index < len(code):
        if quote == '"""':
            if code.startswith('"""', index):
                quote = None
                index += 3
                continue
            index += 1
            continue
        character = code[index]
        if quote is not None:
            if character == "\\":
                index += 2
                continue
            if character == quote:
                quote = None
            index += 1
            continue
        if code.startswith('"""', index):
            quote = '"""'
            index += 3
            continue
        if character in ('"', "'"):
            quote = character
        elif character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return None


def count_discoverable_kotlin_handlers(code: str, contract: dict) -> int:
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", code, flags=re.DOTALL)
    function_headers = re.finditer(
        r"\bfun\s+(?:<[^>{}\n]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_<>?,. ]*\.)?"
        r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(",
        code,
        re.DOTALL,
    )
    functions = []
    for header in function_headers:
        open_index = header.end() - 1
        close_index = find_balanced_parenthesis_end(code, open_index)
        if close_index is None:
            continue
        functions.append(
            {
                "start": header.start(),
                "name": header.group("name"),
                "parameters": code[open_index + 1:close_index],
            }
        )
    count = 0
    annotation_pattern = re.compile(
        rf"@(?:[A-Za-z_][A-Za-z0-9_]*\.)*{re.escape(contract['annotation'])}\b"
    )
    parameter_type = contract.get("parameter_type")
    for index, function in enumerate(functions):
        prefix_start = functions[index - 1]["start"] if index else 0
        prefix = code[prefix_start:function["start"]]
        discoverable = (
            function["name"] == contract["default_name"]
            or annotation_pattern.search(prefix) is not None
        )
        if not discoverable:
            continue
        if parameter_type is not None and re.search(
            rf"\b{re.escape(parameter_type)}\b",
            function["parameters"],
        ) is None:
            continue
        count += 1
    return count


def parse_yaml_path_values(text: str) -> tuple[dict[str, str], list[str]]:
    """Parse the simple indented YAML mappings used by configuration evals."""
    stack: list[tuple[int, list[str]]] = []
    values = {}
    invalid_lines = []
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        if not raw_line.strip() or raw_line.lstrip().startswith(("#", "---", "...")):
            continue
        match = re.fullmatch(r"([ \t]*)([^:#][^:]*):(?:[ \t]*(.*))?", raw_line)
        if match is None:
            invalid_lines.append(f"line {line_number}: {raw_line.strip()}")
            continue
        indentation, raw_key, raw_value = match.groups()
        indent = len(indentation.expandtabs(8))
        while stack and indent <= stack[-1][0]:
            stack.pop()
        key = raw_key.strip().strip("'\"")
        key_parts = [part for part in key.split(".") if part]
        path_parts = [part for _, parts in stack for part in parts] + key_parts
        value = (raw_value or "").strip()
        if not value or value.startswith("#"):
            stack.append((indent, key_parts))
            continue
        value = re.sub(r"[ \t]+#.*$", "", value).strip().strip("'\"")
        values[".".join(path_parts)] = value
    return values, invalid_lines


def precheck_output(eval_case: dict, output: str) -> list[str]:
    output = output.replace("\r\n", "\n")
    fenced_code = extract_fenced_code(output)
    failures = []
    checks = eval_case["output_checks"]
    for check in checks["must_contain"]:
        target = check_text(check, output, fenced_code)
        if "yaml_path_values" in check:
            yaml_values, invalid_lines = parse_yaml_path_values(target)
            mismatches = {
                path: {"expected": expected, "actual": yaml_values.get(path)}
                for path, expected in check["yaml_path_values"].items()
                if yaml_values.get(path) != expected
            }
            if mismatches or invalid_lines:
                failures.append(
                    f"{check['id']}: YAML path mismatch {mismatches}; "
                    f"invalid block-style YAML lines {invalid_lines}"
                )
            continue
        if "kotlin_handler_discovery" in check:
            occurrences = count_discoverable_kotlin_handlers(
                target,
                check["kotlin_handler_discovery"],
            )
            min_occurrences = check.get("min_occurrences", 1)
            if occurrences < min_occurrences:
                failures.append(
                    f"{check['id']}: expected at least {min_occurrences} discoverable handler(s), "
                    f"found {occurrences}"
                )
            continue
        if "any_of_regex" in check:
            patterns = check["any_of_regex"]
            occurrences = sum(
                sum(1 for _ in re.finditer(pattern, target)) for pattern in patterns
            )
        else:
            patterns = check["any_of"]
            occurrences = sum(target.count(value) for value in patterns)
        min_occurrences = check.get("min_occurrences", 1)
        if occurrences < min_occurrences:
            failures.append(
                f"{check['id']}: expected at least {min_occurrences} occurrence(s) "
                f"from {patterns}"
            )
    for check in checks["must_not_contain"]:
        target = check_text(check, output, fenced_code)
        if "any_of_regex" in check:
            matched = [
                pattern
                for pattern in check["any_of_regex"]
                if re.search(pattern, target) is not None
            ]
        else:
            matched = [value for value in check["any_of"] if value in target]
        if matched:
            failures.append(f"{check['id']}: forbidden content found {matched}")
    return failures


def select_plugin_skills(plugin: dict, skill_names: set[str]) -> set[str]:
    patterns = plugin["skills"]
    if isinstance(patterns, list):
        return set(patterns) & skill_names
    selected = {
        skill_name
        for skill_name in skill_names
        if any(fnmatch.fnmatch(skill_name, pattern) for pattern in patterns.get("include", ["*"]))
    }
    return {
        skill_name
        for skill_name in selected
        if not any(
            fnmatch.fnmatch(skill_name, pattern)
            for pattern in patterns.get("exclude", ["*-workspace"])
        )
    }


def discover_skill_dirs(skills_root: Path) -> list[Path]:
    require(skills_root.is_dir(), f"Missing skills directory: {skills_root}")
    skill_dirs = []
    for candidate in sorted(skills_root.iterdir()):
        if not candidate.is_dir() or candidate.name.startswith(".") or candidate.name.endswith("-workspace"):
            continue
        require((candidate / "SKILL.md").is_file(), f"Missing SKILL.md in {candidate}")
        skill_dirs.append(candidate)
    return skill_dirs


def validate_package(repo_root: Path) -> tuple[int, int, int]:
    skills_root = repo_root / "skills"
    skill_dirs = discover_skill_dirs(skills_root)
    skill_names = {path.name for path in skill_dirs}
    require(skill_names == SKILL_NAMES, f"skill inventory mismatch: {sorted(skill_names)}")

    for skill_dir in skill_dirs:
        metadata_path = skill_dir / "agents" / "openai.yaml"
        require(metadata_path.is_file(), f"missing {metadata_path.relative_to(repo_root)}")
        validate_agent_metadata(skill_dir.name, load_agent_metadata(metadata_path))

    plugins_path = skills_root / "plugins.json"
    plugins = json.loads(plugins_path.read_text())
    validate_plugins(plugins)
    plugin_selections = []
    for plugin in plugins["plugins"]:
        if isinstance(plugin["skills"], list):
            unknown_skills = set(plugin["skills"]) - skill_names
            require(
                not unknown_skills,
                f"plugin {plugin['name']} references unknown skills: {sorted(unknown_skills)}",
            )
        plugin_selection = select_plugin_skills(plugin, skill_names)
        require(plugin_selection, f"plugin {plugin['name']} selects no skills")
        plugin_selections.append(plugin_selection)
    selected = set().union(*plugin_selections)
    require(selected == skill_names, f"plugins.json selects {sorted(selected)}, expected {sorted(skill_names)}")

    eval_path = repo_root / "scripts" / "skills" / "evals.json"
    eval_document = json.loads(eval_path.read_text())
    validate_eval_document(eval_document, repo_root)
    covered_targets = {eval_case["target_skill"] for eval_case in eval_document["evals"]}
    require(
        covered_targets == skill_names,
        f"eval target coverage mismatch: {sorted(covered_targets)}",
    )
    routing_cases = eval_document["routing_cases"]
    require(len(routing_cases) == 8, "evals.json must define exactly 8 routing_cases")
    eval_targets = {eval_case["id"]: eval_case["target_skill"] for eval_case in eval_document["evals"]}
    direct_targets = {
        eval_targets[case["route_expectation"]["eval_target"]]
        for case in routing_cases
        if case["mode"] == "direct"
    }
    require(direct_targets == skill_names, f"direct routing coverage mismatch: {sorted(direct_targets)}")
    require(
        sum(case["mode"] == "mixed" for case in routing_cases) == 2,
        "routing_cases must include exactly two mixed cases",
    )
    require(
        sum(case["mode"] == "negative" for case in routing_cases) == 2,
        "routing_cases must include exactly two negative cases",
    )
    mixed_sequences = {
        tuple(case["route_expectation"]["skill_sequence"])
        for case in routing_cases
        if case["mode"] == "mixed"
    }
    require(
        ("wow", "wow-debugging") in mixed_sequences,
        "routing_cases must cover wow to wow-debugging handoff",
    )
    require(
        ("wow-code-review", "wow-development-workflow") in mixed_sequences,
        "routing_cases must cover review to development handoff",
    )
    return len(skill_names), len(eval_document["evals"]), len(routing_cases)


def load_eval_document(repo_root: Path) -> dict:
    eval_path = repo_root / "scripts" / "skills" / "evals.json"
    document = json.loads(eval_path.read_text())
    validate_eval_document(document, repo_root)
    return document


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Validate and precheck the Wow skill package.")
    subparsers = parser.add_subparsers(dest="command")
    subparsers.add_parser("validate", help="Validate package metadata and eval contracts.")
    precheck = subparsers.add_parser(
        "precheck",
        help="Run deterministic checks before the required human rubric review.",
    )
    precheck.add_argument("--eval", required=True, dest="eval_id")
    precheck.add_argument("--input", required=True, dest="input_path")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    command = args.command or "validate"
    repo_root = Path(__file__).resolve().parents[2]
    try:
        if command == "validate":
            skill_count, eval_count, routing_count = validate_package(repo_root)
            print(
                f"PASS package_schema skills={skill_count} evals={eval_count} "
                f"routing_cases={routing_count} routing_execution=NOT_RUN"
            )
            return 0
        document = load_eval_document(repo_root)
        eval_case = next(
            (item for item in document["evals"] if item["id"] == args.eval_id),
            None,
        )
        require(eval_case is not None, f"unknown eval id: {args.eval_id}")
        output = sys.stdin.read() if args.input_path == "-" else Path(args.input_path).read_text()
        failures = precheck_output(eval_case, output)
        if failures:
            for failure in failures:
                print(f"PRECHECK_FAIL {failure}")
            return 1
        check_count = sum(len(eval_case["output_checks"][group]) for group in CHECK_GROUPS)
        print(
            f"REVIEW_REQUIRED eval={args.eval_id} deterministic_checks={check_count} "
            "manual_rubric=unverified"
        )
        return 3
    except (ValidationError, json.JSONDecodeError, OSError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
