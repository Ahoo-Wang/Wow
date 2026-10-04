---
name: n01-data-query-elasticsearch-count
tags: [activation, negative, neighbour]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Run an Elasticsearch _count on the orders-* index to tell me how many orders were paid yesterday; Logstash writes that index, there is no Wow service.
