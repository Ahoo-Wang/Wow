---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Uses the generated CartCommandClient (or CommandGateway) and CartStateClient (or CartSnapshotQueryClient) from the service's Manifest, with no tenant or owner in any path: the service takes them from the caller
2. Waits with wait.snapshot() (the default plan) before reading, or reads the state with minVersion set to the command result's version
3. Handles a refusal as a rejected promise: catches WowProblemError and reads its code, rather than inspecting a resolved result
4. Retries only as error.retry says, with the same request id
