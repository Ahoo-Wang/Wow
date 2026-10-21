---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Explains that V10 parks the aggregate, not the event, for that subscription after the in-process retries: its later commits wait while other orders go on
2. Distinguishes a transient outside failure (the mail server) from a handler defect, and says a resume handles the aggregate's commits from the failed version on, so the handler must tolerate a redelivery (the reactor's `invocationKey` as the mail's idempotency key)
3. Names the long-horizon retries (`wow.subscription.parked.*`) and the systemic pause when many aggregates fail at once
4. Treats resume or skip as a production write that needs the user's authorization, and changes nothing
