---
name: b137-migrate-ts-generator-from-openapi
tags: [behavior, typescript, generation, migration]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Our frontend's package.json runs `fetcher-generator generate -i http://localhost:8080/v3/api-docs -o src/generated` and depends on @ahoo-wang/fetcher-generator and @ahoo-wang/fetcher-wow. The order service has just moved to Wow V10. Move the frontend's client generation and runtime to V10. Do not modify files. Give the code and the commands in your answer.
