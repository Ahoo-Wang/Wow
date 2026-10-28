---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Commits the new descriptor over the old one and runs admit, which reports state.remark gone, state.warehouse refused as an analysis dimension, and state.buyer.level deprecated in favour of state.buyer.tier
2. Fixes each finding at its choice: drops the remark, moves level to tier, keeps warehouse out of analysis, and narrows every system view that used them
3. Does not add capabilities merely because the new descriptor grants them
4. Reports the new version, the admit result and the system views affected
