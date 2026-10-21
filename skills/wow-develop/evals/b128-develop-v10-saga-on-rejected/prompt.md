---
name: b128-develop-v10-saga-on-rejected
tags: [behavior, positive, saga]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Our Wow V10 ordering service needs a saga: when an order is submitted, reserve stock in the inventory context (another service, its `inventory-api` jar is on our class path) for each line; when the inventory refuses a reservation, cancel the order with the reason. Write the saga and its test. Do not modify files.
