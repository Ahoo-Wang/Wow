---
name: b131-debug-wait-timeout
tags: [behavior, diagnose, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

A client of our Wow V10 ordering service gets `504` with `code: wow.wait.timeout` on `POST /ordering/order/{id}/submit-order` with `Wow-Wait: snapshot > handled(OrderProcessSaga.onOrderSubmitted)`. Retrying sometimes says the order is already submitted. Find the first failing stage; do not change anything.
