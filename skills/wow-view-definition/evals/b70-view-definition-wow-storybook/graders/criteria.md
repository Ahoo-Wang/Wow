---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Puts the definition and system view in the retail scenario's modules and admits it over the committed descriptor
2. Writes a *.stories.tsx story with a *.test.stories.tsx twin that asserts what the audience sees and that the view is not refused
3. Keeps payload conditions inside ELEMENT_MATCH on body with the bodyType condition
4. Runs the Storybook type check, lint and interaction tests and reports each exit code, without changing the view engine itself
