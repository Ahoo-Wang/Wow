---
name: b48-client-error-handling
tags: [behavior, errors, command]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

In a React form built on a generated CartCommandClient, show the server's field validation messages next to the inputs when add_cart_item is refused, and a toast for any other failure.
