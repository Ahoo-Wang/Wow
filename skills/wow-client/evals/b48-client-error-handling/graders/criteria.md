---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Wraps the command call in try/catch rather than inspecting a returned errorCode: a refused or failed command rejects
2. Reads the failure with await toWowError(error) and treats undefined as a network, timeout or abort failure
3. Maps wowError.bindingErrors (name, msg) to the form fields when errorCode is ErrorCodes.COMMAND_VALIDATION
4. Resends only after no Wow answer and with the same requestId; does not resend after RequestTimeout or DuplicateRequestId
