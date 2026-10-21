---
type: regex
pattern: 'import --(final|restart)'
match: contains
target: last_message
---

The answer names the final pass, `import --final` or `import --restart`.
