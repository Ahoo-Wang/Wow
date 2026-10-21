---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Wraps the command call in try/catch: a refused or failed command rejects with WowProblemError, it never resolves with a failure
2. Maps error.violations (path, message) to the form fields for a validation problem, and shows the domain code from the generated error codes, or the problem's detail, in the toast for anything else
3. Treats an error that is not a WowProblemError as a network, timeout or abort failure
4. Resends only when error.retry says so and with the same request id; after wow.wait.timeout reads the state first, since the command may have committed
