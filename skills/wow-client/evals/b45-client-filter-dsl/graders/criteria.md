---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Builds the filter with filter.and([...]), filter.eq and filter.yesterday with zoneId 'Asia/Shanghai' (without it the server reads the day in UTC), not hand-written JSON
2. Sorts with desc('state.createdAt') and paginates with pagination { index: 1, size: 20 }, calling paged on the OrderSnapshotQueryClient
3. Writes the projection as a literal projection: { include: ['aggregateId', 'state.total'] } so the records are typed to those paths
4. Imports the builders from @ahoo-wang/wow-client/dsl (or the root entry)
