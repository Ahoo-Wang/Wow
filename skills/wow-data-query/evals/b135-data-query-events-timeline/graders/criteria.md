---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Uses the events model of the order aggregate (GET then POST /{context}/order/events/query), not the snapshot, with a filter naming the aggregate (AGGREGATE_ID o-1042), sorted by version ascending
2. Treats each record as one commit (version, commitTime, commandType, its events by name) and lists the business changes with their times and time zone
3. Notes that event names are the current ones and bodies upgraded to the current revision, and that retired events are not returned
4. Mentions the state read GET /{context}/order/o-1042/state(/trace) as the way to see the state at a version, and sends no command
