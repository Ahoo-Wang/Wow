---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Generates from a committed OpenAPI document (or a pinned service URL with -H credentials and --timeout) into a directory owned only by the generator, and commits the .wow-generator.json manifest
2. Runs wow-generator generate with --strict in CI, then fails when git status --porcelain (or git diff --exit-code after staging) reports changes under the output directory
3. Treats exit codes by kind: 2 input, 3 configuration, 4 specification or --strict warnings, 1 internal
4. Type-checks the project after regenerating
