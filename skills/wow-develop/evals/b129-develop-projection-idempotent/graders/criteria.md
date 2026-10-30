---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Writes a `@Projection` whose handler upserts with a guard by the commit's version, because a commit can be handled more than once (retries, an operator's retry after parking, a rebuild)
2. Marks the JDBC handler `@Blocking` (or makes it non-blocking), since a synchronous handler otherwise runs on the shared worker
3. Notes that a new projection starts from the beginning of the log by default, rebuilding the table from history
4. Tests with `projectionSpec` through a fake of the table port: the same commit twice and an older version after a newer one leave the newer row
