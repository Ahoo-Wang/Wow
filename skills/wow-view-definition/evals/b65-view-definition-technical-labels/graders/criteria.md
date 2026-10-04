---
type: llm
weight: 1
---

Judge only the agent's final answer. It worked in an empty, read-only directory with no access to the user's project, the Wow sources or any running service, so ignore that it wrote no files, could not find the user's code, hedged, or asked follow-up questions: grade the plan, code and explanation it gave. Where a point below asks it to read, run, verify or change something, the point passes when the answer names that concrete step (what to read, run or change, and what it would show) and does not claim to have done it. Accept any wording, language and equivalent code.

PASS only if the answer does all of these:

1. Pushes back and writes labels, option labels, recordNoun and system view titles in the audience's business words
2. Writes them as text(key) with a words table for each language the host serves (for example 中文 and English), and admit reports no definition.text.unknown in either
3. Uses the engine's analysis vocabulary for display names (记录数/Record count, 维度, 指标) instead of field-and-function names such as SUM(paidAmount)
