---
type: tool_used
tool: Skill
input_match: '"skill"\s*:\s*"(?:[\w-]+:)?wow-generator"'
min: 0
max: 0
arm: both
---

The request is outside every Wow skill, so $wow-generator must not load. Scored in both arms (`arm: both`): the without-skill arm passes trivially, so a negative case measures trigger precision, not a with/without delta.
