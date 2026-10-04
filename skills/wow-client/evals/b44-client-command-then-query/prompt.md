---
name: b44-client-command-then-query
tags: [behavior, command, query]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

In a TypeScript app, send an add_cart_item command to the owner-scoped cart aggregate at owner/{ownerId}/cart, wait for the snapshot, then read the updated cart state.
