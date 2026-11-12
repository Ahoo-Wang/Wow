# The subscriptions server

The benchmark server with no-op projections of the account. It answers one question for capacity planning: what one more subscription costs a node and its log store.

## What it runs

The benchmark server as it is: the account, the transfer saga and the snapshot subscription. Beside them run `benchmark.projections` projections, `NoOpProjection1.onEvent` to `NoOpProjection<n>.onEvent`, from 0 (the default) to 16.

- Each projection is a subscription of its own over the account's log.
- It takes every commit with the account's state after it, as a projection that writes a read model does.
- It returns at once.

A hand-written `ModelProvider` (`NoOpProjections`) binds them, so their number is a property, and one distribution measures every count. The account's model and the saga stay the generated provider's.

## What a no-op projection leaves out

**The figure is the framework's share of a subscription, not the cost of yours.** What it does include:

- reading the log's tail per partition;
- deciding which commits the subscription takes;
- handing each one over with its state;
- saving its checkpoint (`wow.subscription.checkpoint-interval`, 1 s by default);
- its lag and backlog accounting.

It leaves out:

- **The handler's own work:** mapping a state to a row, any call it makes.
- **The handler's store:** the read model's database, its writes, its indexes and its connections. That is usually the larger part of a real projection's cost, and it is on that store, not on Wow's log store.
- **Rebuilds of a state the shared states no longer hold.** A projection that keeps up never needs one. One that falls behind does (see Scaling and Capacity in the docs).
- **A saga's commands.** A saga derives commands, and each costs what a command costs.

A real projection's cost is this server's figure plus its handler's and its store's, measured in your service.

## How it is measured

Locally, with the same distribution at every count, so the classpath, start-up and memory do not change between arms. E1 at a fixed rate below the node's knee, on the stores the snapshot uses, one count after another and then in reverse (0, 1, 2, 4, 4, 2, 1, 0):

```bash
./gradlew :benchmark-subscriptions-server:installDist
test/benchmarks/harness/bench.sh run --backend=postgres --accounts=100000 --prime --scenario=E1 --rate=<rate> \
  --install=test/benchmarks/server/build/install/benchmark-subscriptions-server --server-args="--benchmark.projections=<n>"
```

Per command, against the count:

- the node's CPU;
- the log store's CPU and round trips (commits or commands);
- the checkpoint writes;
- the p50 and p99.

The slope is the cost of one subscription. The points at one, two and four say whether that cost is linear.
