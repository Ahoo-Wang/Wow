---
name: b49-client-cosec-auth
tags: [behavior, auth, cosec]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

The Wow V10 order service sits behind CoSec. Wire the browser app so every generated Wow client sends the user's token and the application id, and refreshes the token when it expires.
