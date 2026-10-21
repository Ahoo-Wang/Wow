---
title: Platform Services
description: What becomes of 9.x's platform services in V10, the compensation service, the view store and the dataset registry, and how to move each one.
outline: deep
---

# Platform Services

9.x shipped three services beside the framework: the compensation service, the view store and the dataset registry. V10 keeps the view store, replaces the compensation service with the ops service, and replaces the dataset registry with the analytics catalog. This page says what changes for each and how to move it. [9.x Capabilities in V10](./capabilities) lists every capability.

| 9.x | V10 | Data moved by |
| --- | --- | --- |
| Compensation service | The ops service and console, over each service's failure ledger | `migrate-data`, with each service's history |
| View store | The V10 view store | `migrate-data`, as any service's history |
| Dataset registry | The analytics catalog (incubating) | Redefined, not moved |

## Compensation service → ops service

In 9.x, a failed handling was recorded by the compensation service, which retried it and let operators skip it. In V10, nothing is skipped and nothing leaves the service: a handler that keeps failing parks its aggregate in the service's own failure ledger, beside its checkpoints, and the parked aggregate is retried over a longer horizon. The ops service collects the ledgers of every service, and its console is where operators look at the parked aggregates and resume or skip them ([The Ops Service and Console](../operate/ops-console)).

To move:

1. Deploy the ops service, and connect every V10 service to it.
2. When migrating each service's data, give `migrate-data` the 9.x compensation service's database. It imports each unresolved execution (`FAILED` or `PREPARED`) as a parked aggregate of the mapped V10 subscription, from the failed version, with its retry state ([Migrate the Data](./data)).
3. Stop the 9.x compensation service with the 9.x application, then run the final pass.

After the cut-over, the V10 services own the retries. Webhooks that told a team about 9.x failures move to the ops service's webhook, which tells it when an aggregate parks or spends its retries.

## View store → V10 view store

The view store, which keeps the views and dashboards the view engine saves, is kept, rewritten on V10. It is a Wow service like any other, so its data moves the way any service's does: `migrate-data` imports the 9.x view history into its log.

The V10 view store reads that history as it is:

- 9.x recorded no `kind` on a view; revision 2 of `ViewCreated` and `ViewSaved` does. Upgrade functions take the kind from the 9.x view's config, and a 9.x view whose config has none fails the read, naming the view ([Evolve Events](../guide/event-evolution) shows the upgrade).
- 9.x's move of a view to another audience, an owner transfer, is kept as a history-only event, `ViewAudienceChanged`: the view still loads with its audience and owner, and no V10 command produces it again.

The view engine and its clients talk to the V10 view store through its V10 routes.

## Dataset registry → analytics catalog

The 9.x dataset registry described datasets for the analytics service. In V10, the control plane of analytics is the analytics catalog: it knows the warehouse's tables as sources (a table the ClickHouse sink keeps is described by its `wow_catalog` row), defines datasets as wide tables over them, and publishes each version as a ClickHouse view that every service node serves under `/wow/dataset/<context>/<name>/query`. A dataset's rows take the caller's scope, and each column's masking follows its sources.

The analytics catalog is incubating and not yet published as a release artifact. The 9.x dataset definitions are not moved: datasets are defined again over the V10 warehouse, whose tables differ from 9.x's. A service can also declare datasets of its own tables in `META-INF/wow/dataset/` without the catalog ([ClickHouse](../operate/clickhouse#datasets)).
