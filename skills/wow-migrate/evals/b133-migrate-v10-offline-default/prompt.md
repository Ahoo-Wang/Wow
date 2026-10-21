---
name: b133-migrate-v10-offline-default
tags: [behavior, migration, data-cutover, planning, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Our Wow 9.x booking service (MongoDB event store, about 3 million streams) is ready to move to V10; the source migration and migration-map.json are done. We can take a two-hour maintenance window on Sunday night. Plan the data cut-over with wow-migrate-data. No production access is granted in this conversation.
