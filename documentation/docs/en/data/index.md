---
title: Data in a Wow System
description: The three models a Wow system answers queries over, snapshots, event streams and datasets, what each holds, and how a query learns what it may ask and see.
---

# Data in a Wow System

Every aggregate's history is in the event log, and what is queried is read from it. A Wow system answers queries over three models, all in one [query language](../reference/query-language):

| Model | One record is | Route | Read it for |
| --- | --- | --- | --- |
| Snapshots | An aggregate's latest state, with its system fields | `POST /<context>/<aggregate>/snapshot/query` | What things are now: open orders, products by price |
| Event streams | One commit of an aggregate, with its events | `POST /<context>/<aggregate>/events/query` | What happened and when: orders cancelled last week, a product's price changes |
| Datasets | A row of a table for analysis, over the warehouse | `POST /wow/dataset/<context>/<name>/query` | Analyses across aggregates and services: sales by region and month |

Snapshots and event streams are served by the service that owns the aggregate, from its own store, as soon as it commits. Datasets are read from the warehouse the ClickHouse sink fills from the log, a little behind it, and are declared by a service ([Publish Datasets from a Service](../guide/datasets)) or defined in the analytics catalog ([Datasets and the Analytics Catalog](./datasets)).

## What a model admits

Each model describes itself. A `GET` on its query route answers its capability descriptor: every field with its types, the operators its filter takes, whether it sorts, groups and aggregates, the collections a query can reach into, and the limits. The descriptor carries a digest of itself as its `ETag`, so a client caches it and asks again with `If-None-Match`. A query that asks for something the model does not admit is refused before it runs, each violation naming its rule ([Errors](../reference/query-language#errors)).

## What a caller sees

A query never names its caller's scope; the service applies it. Each caller reads only the records of its own tenant, and of its own owner and space where the aggregate keeps them; a caller allowed to act across scopes names another in `Wow-Scope`. A dataset's rows carry the tenant, owner and space of their subject, so the same rules hold there. A field declared sensitive is masked in every answer, for every caller: a `DISPLAY` field can still be filtered and sorted, a `CONFIDENTIAL` one cannot ([Identity](../concepts/identity)).

## Where to go next

| Task | Read |
| --- | --- |
| Filter, group and measure as an analyst | [Query for Analysis](./querying) |
| Define a dataset over the warehouse, review it and publish it | [Datasets and the Analytics Catalog](./datasets) |
| Look up an operator, a query kind or an error | [Query Language](../reference/query-language) |
| Let users build and save their own views | [The Workbench](./workbench) |
| Query from TypeScript | [Read and Query](../typescript/queries) |
| Query from a service, in Kotlin | [Query Snapshots](../guide/queries), [Query Event Streams](../guide/event-queries) |
