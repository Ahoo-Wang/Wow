---
name: b127-migrate-v10-tags-after-import
tags: [behavior, migration, data-cutover, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

After importing our 9.x orders into V10, `migrate-data compare` exits 3 and reports `$tags` differences on about 4% of orders, all of which changed their shipping province at some point. Our ABAC rule lets regional staff see orders tagged with their province. Is the import broken? Can we cut over?
