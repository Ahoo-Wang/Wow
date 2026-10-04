---
type: tool_used
tool: Skill
input_match: '"skill"\s*:\s*"(?:[\w-]+:)?wow-data-query"'
min: 0
max: 0
arm: both
---

The request belongs to $wow-debug, so $wow-data-query must not load. Scored in both arms (`arm: both`): the without-skill arm passes trivially, so a negative case measures trigger precision, not a with/without delta.
