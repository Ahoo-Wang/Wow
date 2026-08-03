"""Wow Skill validator trace schema primitives."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .core import TRACE_SCHEMA_RELATIVE_PATH, TRACE_SCHEMA_VERSION, Validation, read_json

__all__ = ['validate_trace_schema']


def validate_trace_schema(repo_root: Path, validation: Validation) -> None:
    source = repo_root / TRACE_SCHEMA_RELATIVE_PATH
    schema = read_json(source, validation)
    if schema is None:
        return
    if not isinstance(schema, dict):
        validation.error(f"{source}: trace schema must be a JSON object")
        return

    def require(condition: bool, contract: str) -> None:
        if not condition:
            validation.error(f"{source}: trace schema must define {contract}")

    def mapping(value: Any) -> dict[str, Any]:
        return value if isinstance(value, dict) else {}

    def exact_string_set(value: Any, expected: set[str]) -> bool:
        return (
            isinstance(value, list)
            and all(isinstance(item, str) for item in value)
            and set(value) == expected
        )

    root_required = {
        "schemaVersion",
        "runId",
        "requestSha256",
        "adapter",
        "output",
        "processExitCode",
        "events",
        "sandbox",
        "attestation",
    }
    require(
        schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema",
        "JSON Schema draft 2020-12",
    )
    require(schema.get("type") == "object", "an object root")
    require(schema.get("additionalProperties") is False, "a closed object root")
    require(
        exact_string_set(schema.get("required"), root_required),
        "the exact root fields",
    )
    properties = schema.get("properties")
    require(isinstance(properties, dict), "root properties")
    if isinstance(properties, dict):
        require(set(properties) == root_required, "only the exact root properties")
        require(
            properties.get("schemaVersion") == {"const": TRACE_SCHEMA_VERSION},
            f"schemaVersion {TRACE_SCHEMA_VERSION}",
        )
        require(
            properties.get("requestSha256")
            == {
                "type": "string",
                "description": (
                    "SHA-256 of the exact UTF-8 file bytes stored in request.json, "
                    "including formatting whitespace and the trailing newline."
                ),
                "pattern": "^[0-9a-f]{64}$",
            },
            "a lowercase SHA-256 request hash",
        )
        adapter = properties.get("adapter")
        expected_capabilities = {
            "activation-trace",
            "tool-trace",
            "command-policy",
            "workspace-policy",
            "activation-only",
        }
        if isinstance(adapter, dict):
            adapter_properties = adapter.get("properties")
            require(
                exact_string_set(
                    adapter.get("required"),
                    {"name", "version", "capabilities", "freshTask", "promptExact"},
                ),
                "the exact adapter fields",
            )
            require(adapter.get("additionalProperties") is False, "a closed adapter")
            require(isinstance(adapter_properties, dict), "adapter properties")
            if isinstance(adapter_properties, dict):
                capabilities = mapping(adapter_properties.get("capabilities"))
                capability_items = mapping(capabilities.get("items"))
                require(
                    capabilities.get("uniqueItems") is True
                    and exact_string_set(
                        capability_items.get("enum"), expected_capabilities
                    ),
                    "the exact adapter capabilities",
                )
                require(
                    adapter_properties.get("freshTask") == {"const": True}
                    and adapter_properties.get("promptExact") == {"const": True},
                    "freshTask and promptExact attestations",
                )
        else:
            require(False, "an adapter object")
        events = properties.get("events")
        event_items = mapping(mapping(events).get("items"))
        require(
            isinstance(events, dict)
            and events.get("type") == "array"
            and event_items.get("oneOf")
            == [
                {"$ref": "#/$defs/activationEvent"},
                {"$ref": "#/$defs/accessEvent"},
                {"$ref": "#/$defs/commandEvent"},
            ],
            "the activation, access, and command event union",
        )

    definitions = schema.get("$defs")
    expected_definitions = {
        "activationEvent",
        "accessEvent",
        "commandEvent",
        "sandbox",
        "attestation",
    }
    require(isinstance(definitions, dict), "event and attestation definitions")
    if not isinstance(definitions, dict):
        return
    require(set(definitions) == expected_definitions, "only the exact definitions")

    expected_required = {
        "activationEvent": {"seq", "type", "skill", "primary"},
        "accessEvent": {"seq", "type", "path"},
        "commandEvent": {"seq", "type", "argv", "cwd", "executable", "exitCode"},
        "sandbox": {
            "externalReadBlocked",
            "externalMutationBlocked",
            "networkBlocked",
            "connectorsBlocked",
            "evalContentBlocked",
            "activationOnly",
            "commandPolicyEnforced",
        },
        "attestation": {"algorithm", "keyId", "signature"},
    }
    for name, required_fields in expected_required.items():
        definition = definitions.get(name)
        require(isinstance(definition, dict), f"a {name} object")
        if not isinstance(definition, dict):
            continue
        require(definition.get("type") == "object", f"{name} as an object")
        require(definition.get("additionalProperties") is False, f"a closed {name}")
        require(
            exact_string_set(definition.get("required"), required_fields),
            f"the exact {name} fields",
        )
        require(
            isinstance(definition.get("properties"), dict)
            and set(definition["properties"]) == required_fields,
            f"only the exact {name} properties",
        )

    activation_properties = mapping(
        mapping(definitions.get("activationEvent")).get("properties")
    )
    access_properties = mapping(
        mapping(definitions.get("accessEvent")).get("properties")
    )
    command_properties = mapping(
        mapping(definitions.get("commandEvent")).get("properties")
    )
    attestation_properties = mapping(
        mapping(definitions.get("attestation")).get("properties")
    )
    require(
        activation_properties.get("skill") == {"type": "string", "minLength": 1},
        "a non-empty activation skill",
    )
    require(
        access_properties.get("path") == {"type": "string", "minLength": 1},
        "a non-empty access path",
    )
    require(
        mapping(command_properties.get("argv")).get("minItems") == 1
        and mapping(command_properties.get("argv")).get("items")
        == {"type": "string", "minLength": 1},
        "a non-empty command argv",
    )
    require(
        command_properties.get("executable") == {"type": "string", "pattern": "^/"},
        "an absolute command executable",
    )
    require(
        attestation_properties.get("algorithm") == {"const": "hmac-sha256"}
        and attestation_properties.get("signature")
        == {"type": "string", "pattern": "^[0-9a-f]{64}$"},
        "an HMAC-SHA256 attestation",
    )
