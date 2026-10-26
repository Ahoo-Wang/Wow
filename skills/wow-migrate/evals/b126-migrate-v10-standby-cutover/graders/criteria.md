---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Treats this as the online option (the user wants the shortest stop and the tail already runs; offline is the default otherwise), and runs the V10 nodes through the dual-run with wow.node.standby=true: they serve reads and queries of the imported data, refuse commands with 503 wow.node.standby and run no subscriptions, so V10 writes nothing before the switch
2. Stops every 9.x writer (the compensation service too), then uses import --final because the tail ran continuously, and says it refuses and writes nothing (then import --restart) when an aggregate's import is incomplete or its tail's last pass is too old
3. Keeps compare as the gate after the final pass, with snapshot and backfill-tags before the resume, and stops on unexplained differences while the nodes are still on standby
4. Ends the write stop by POST /wow/node/resume on each node's internal port with the cluster token and a body naming who resumes and why, checks every node is resumed, then switches traffic
5. Asks for each production authorization separately and claims to have run nothing
