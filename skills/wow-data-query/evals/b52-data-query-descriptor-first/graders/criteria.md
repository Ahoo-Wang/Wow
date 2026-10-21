---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Confirms the environment is staging (not production) and uses credentials from the environment without asking for or printing a token
2. Reads the descriptor first, GET on the snapshot query route (/{context}/{aggregate}/snapshot/query), and records its version (the ETag)
3. Maps status, amount and createdAt to descriptor paths and checks EQ, RECENT_DAYS (or a range), DATE_HISTOGRAM and SUM are listed for those fields before sending
4. POSTs a body with "kind": "aggregate" to that route with a DATE_HISTOGRAM in Asia/Shanghai (zoneId and timeZone set, since UTC is the default) and a SUM metric, and never calls a command or rebuild route
5. Answers per-day revenue with units and time zone first, then the exact request body, and mentions any approximate metric or limit caveat
