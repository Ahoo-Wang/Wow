---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Runs the rehearsal on an isolated, empty V10 target with the V10 Manifests and the services' domain jars on the tool's classpath, and keeps rehearsal, production reads, production writes and the traffic switch as separate authorizations
2. Orders the tool's steps: check (the map against the 9.x store and the Manifests) before import, import resumed after an interruption without duplicates, compare, snapshot, and backfill-tags before ABAC event timelines
3. Treats compare's exit 3 as a gate: every kind of difference explained as a known 9.x defect or an intended change and signed off, unexplained kinds block the cut-over, and the share of instances with a 9.x snapshot is reported
4. Plans the cut-over offline by default: stop 9.x's writers (the compensation service too), then import, compare, snapshot and backfill-tags once, then start V10; it times that import on production-sized data in the rehearsal, and offers tail, standby and a final pass (import --final or --restart) only as the option when the import does not fit the stop
5. Lists what it could not verify (the real 9.x data, timings at production size, the rollback) as MISSING EVIDENCE and does not claim to have run anything
