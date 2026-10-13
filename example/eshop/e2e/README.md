# eShop end-to-end tests and load baseline

`:eshop-e2e` runs the eShop's services together, each in a JVM of its own; the suites are listed in the eShop's [README](../README.md#tests). This page is about its load script and holds its baseline.

The baseline is one run of the load script (work package E.6) on one developer machine. It is a reference point for later runs on the same kind of machine, not a benchmark: every service, its backends and the load generator share the machine. It lives in this README because the repository keeps Markdown only in the places its documentation governance names.

## What the load script measures

`EshopLoad` (`src/integrationTest/kotlin/.../EshopLoad.kt`) starts the five services the way `EshopFullChainTest` does (`EshopStack`): each service in a JVM of its own, on PostgreSQL, Kafka and ClickHouse in containers, with the ordering's order stream written to ClickHouse by the Sink. Then a number of buyers at a time (the concurrency) each:

1. add one product to the basket;
2. check the basket out with the `Wow-Wait` that follows the order through 15 hops, until the payment's `PayOrder` is committed in the ordering context (`EshopStack.CHECKOUT_TO_PAID`).

A checkout's latency is that one request's: from the checkout to the paid order, through the basket's checkout saga, the ordering's order process, the inventory's reservation, the ordering's stock confirmation on the inventory's stream, and the payment's request, gateway and outcome. The throughput is the measured checkouts over the measured phase's wall time, the basket additions included. A warm-up of 40 checkouts runs first and is not counted. Every measured checkout ended with the paid order.

## Result

Commit `285da855678f1bbca710da66a536cedd5ddecfb8` (branch `claude/v10-eshop-e2e`), 2026-10-13.

| Concurrency | Checkouts | Wall time | Throughput | p50 | p95 | p99 | Max |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 100 | 38.6 s | 2.6 /s | 350 ms | 398 ms | 408 ms | 415 ms |
| 8 | 200 | 9.6 s | 20.8 /s | 354 ms | 399 ms | 410 ms | 411 ms |
| 32 | 400 | 7.2 s | 55.7 /s | 482 ms | 688 ms | 861 ms | 917 ms |

- The latency floor, about 350 ms with one buyer, is the chain's: 15 hops across four services, five of them over Kafka topics, each read by a subscription that polls.
- Up to 8 buyers, the checkouts overlap without slowing each other. At 32, the latency grows while the throughput still rises: the machine, not the chain, sets the limit there.

## Machine

- Apple M4, 10 cores, 16 GB, macOS 27.0.1.
- Docker Desktop 29.8.2, its VM given 10 CPUs and 8 GB: `postgres:17-alpine`, `confluentinc/cp-kafka:7.4.0`, `clickhouse/clickhouse-server:24.8.14.39-alpine`.
- JDK 21.0.12 (Eclipse Temurin), the services' JVMs with their default heap; the log of 4 partitions, `EshopStack`'s short cluster timings.

## Reproducing it

From the repository root, with JDK 21 and Docker:

```bash
./gradlew -Pwow.scope=v10 :eshop-e2e:eshopLoad -Peshop.load.concurrency=1 -Peshop.load.checkouts=100
./gradlew -Pwow.scope=v10 :eshop-e2e:eshopLoad                                                       # 8 buyers, 200 checkouts
./gradlew -Pwow.scope=v10 :eshop-e2e:eshopLoad -Peshop.load.concurrency=32 -Peshop.load.checkouts=400
```

`-Peshop.load.warm-up` sets the warm-up (40). The script prints one line per run, `[eshop-load] measured: …`, and fails when a checkout does not end with the paid order. It is not part of any check or CI workflow.
