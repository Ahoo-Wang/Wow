---
type: regex
pattern: '''state\.status'''
match: contains
target: last_message
---

The answer declares `'state.status'` as a field path in the definition's code. The descriptor JSON quotes paths with double quotes, so quoting the descriptor does not match.
