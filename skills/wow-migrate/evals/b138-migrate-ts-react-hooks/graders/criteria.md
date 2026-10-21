---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Moves the hooks to the V10 @ahoo-wang/wow-react (React 19 or later) and the client to the V10 @ahoo-wang/wow-client, generated from the service's Manifest
2. Rewrites the hook as usePagedQuery({ query, execute: (q, options) => client.paged(q, options) }) over the generated OrderSnapshotQueryClient, passing the fields type as the second type argument
3. Reads records whole (record.state) instead of the 9.x pagedState, with state.* field paths, and handles errors as WowProblemError
4. Runs tsc, lint and the tests, and lists what it could not verify as MISSING EVIDENCE
