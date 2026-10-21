---
name: a125-migrate-v10-cutover-rollback
tags: [activation, trigger, data-cutover]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Plan the blue-green cut-over of our Wow 9.x inventory service to V10 with wow-migrate-data: the final import pass, the compare gate, and how we roll back if V10 has already written.
