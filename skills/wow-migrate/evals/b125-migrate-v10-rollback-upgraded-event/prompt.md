---
name: b125-migrate-v10-rollback-upgraded-event
tags: [behavior, migration, data-cutover, rollback, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

We cut our order service over from Wow 9.x to V10 yesterday and V10 has been taking orders since. Now we want to go back to 9.x. Our migration-map.json imports the 9.x `order_created` as `OrderCreated` with `toRevision: 1`, and the V10 `OrderCreated` is at revision 2 with an `@Upgrade` from 1. Can we just redeploy 9.x? If not, give the steps. No production access is granted in this conversation.
