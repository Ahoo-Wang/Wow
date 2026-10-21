---
name: b129-develop-v10-projection-idempotent
tags: [behavior, positive, projection]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

In our Wow V10 service, add a projection that keeps an order summary table (order id, status, total) in PostgreSQL through JDBC. Make it safe and tested. Do not modify files.
