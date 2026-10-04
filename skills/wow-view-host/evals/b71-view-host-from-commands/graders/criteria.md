---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Declares both with actions([...]) and binds them on the orders resource with bind, rather than drawing buttons or using slots
2. Reads availability from the record's state with the aggregate's own rule, returns a text(key) reason in business words when refused, and lists the state fields the rules read in the definition's record.rowFields
3. Makes cancel tone danger with a confirmation asked for one record too and a form for the reason; makes ship primary with a confirmation asked for one record too (ask left at 'always', not 'bulk', since shipping cannot be taken back) and no danger tone
4. Has run send through the command client to the aggregate id (read off the row where the row key is a business key), waiting for CommandStage.SNAPSHOT and resolve only after, throwing on refusal
5. Tests the declarations with actionHarness: places, reasons, the bulk split, what a press asks, and run calling the right command
