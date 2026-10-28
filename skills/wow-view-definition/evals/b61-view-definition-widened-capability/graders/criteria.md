---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. It had no shell and no write tool: where a point asks for a written or generated file, an edit, a command run or its result, the code, the command and the expected result shown in the answer satisfy it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Sees state.remark grants no condition and no sort (it is stored but not indexed), and the descriptor has no record.search
2. Does not declare CONTAINS, a search field or a sort on the remark, and does not raise a limit above the descriptor's aggregation.maxLimit of 1000
3. Explains that the definition only narrows what the store grants and that the capability needs a server or storage change (for example a text index or Elasticsearch), and lets admit confirm it
