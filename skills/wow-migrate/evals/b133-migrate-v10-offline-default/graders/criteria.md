---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Chooses the offline cut-over, the default: stop every 9.x writer (the compensation service too) at the start of the window, back up, check and import, compare as the gate, snapshot and backfill-tags, start V10 and probe it, move the bus consumers, switch traffic
2. Makes the choice depend on evidence: a rehearsal on an isolated target with production-sized data times the whole import with compare, snapshot and backfill-tags against the two hours, and only if it does not fit turns to the online option (tail, standby nodes, import --final)
3. Does not plan tail, standby nodes or a final pass for the default path
4. States the rollback before V10 writes (restart 9.x on its untouched stores) and after (export --dry-run's VERDICT, then export), and lists events past the map's toRevision as the end of the rollback window
5. Asks for each production authorization separately and claims to have run nothing
