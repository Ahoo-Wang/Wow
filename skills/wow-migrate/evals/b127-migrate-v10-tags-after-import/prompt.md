---
name: b127-migrate-v10-tags-after-import
tags: [behavior, migration, data-cutover, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

After importing our 9.x orders into V10, `migrate-data compare` exits 3 and reports `$access.province` differences on about 4% of orders, all of which changed their shipping province at some point; `check` had warned that the snapshots of the derived key `address-province`, mapped to the single attribute `province`, hold two values or more. Our access policy lets regional staff see the orders of their province (`OrderStateAccess.PROVINCE isIn caller.claims("province")`). Is the import broken? Can we cut over? Give the code and the commands in your answer.
