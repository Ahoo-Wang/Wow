---
type: regex
pattern: 'state\.status\b[^\n]{0,80}SHIPPED|SHIPPED[^\n]{0,80}state\.status\b'
match: contains
target: last_message
---

The answer queries `state.status` for SHIPPED. The prompt names only `state.oldStatus`, and the descriptor names `state.status` without the value, so quoting either does not match.
