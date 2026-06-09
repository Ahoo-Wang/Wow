# sendAndWaitForSent Fast Path Design

## Context

The current noop benchmark branch already reduced `WaitingFor.waitingLast()` overhead for the single empty-result signal case and added `WaitNotifyComponentBenchmark` coverage. The remaining quick E2E data still shows `CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store)` allocating about `6060 B/op`, while the component data shows the local wait-notify path still spends allocations in wait strategy registration, propagation, sink subscription, and result conversion.

`sendAndWaitForSent(command)` is a narrower contract than the generic wait APIs. It only waits until the command has been accepted by the `CommandBus`; it does not need downstream wait propagation because no later processing stage is involved. The generic APIs must keep their existing behavior because callers may pass custom wait strategies and rely on header propagation.

## Decision

Implement a dedicated `DefaultCommandGateway.sendAndWaitForSent(command)` override. The override will:

- run the same validation and idempotency checks as `send(command)`;
- call `commandBus.send(command)`;
- on success, build a `SENT` `CommandResult` directly from `command.commandSentSignal(command.commandId).toResult(command)`;
- on failure, keep the existing error surface by throwing `CommandResultException` with a `SENT` failure result;
- skip wait header propagation, wait strategy registration, sink notification, and `waitingLast()` for this convenience API.

The implementation will not change:

- `CommandGateway.sendAndWait(command, waitStrategy)`;
- `CommandGateway.send(command, waitStrategy)`;
- `CommandGateway.sendAndWaitStream(command, waitStrategy)`;
- extracted wait strategy propagation from headers;
- processed, snapshot, projected, event-handled, saga-handled, or waiting-chain behavior.

## Compatibility Boundary

The only intentional behavior change is internal side effects for `sendAndWaitForSent(command)`: it will no longer write `command_wait_*` headers or register a `WaitingForSent` strategy. This is acceptable because the convenience API completes at the gateway `SENT` stage and does not require downstream notification.

The generic `sendAndWait(command, WaitingForStage.sent(...))` path remains available for callers that explicitly want the full wait strategy machinery and header propagation.

## Tests

Add focused tests in `DefaultCommandGatewayTest`:

- `sendAndWaitForSent` returns a successful `SENT` result and sends the command;
- it does not propagate command wait headers, register wait strategies, or notify wait notifiers;
- command bus failure is mapped to `CommandResultException` with a `SENT` result;
- void commands are accepted on this path.

Run the targeted gateway tests, full `:wow-core:test`, and `:wow-core:check`.

## Benchmark Proof

Use quick E2E as the proof loop:

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport --stacktrace
```

Compare before/after `CommandWriteE2EBenchmark.sendAndWaitSent (scenario=noop-store)` for:

- `gc.alloc.rate.norm`;
- throughput;
- average time.

The expected result is lower `B/op` on the noop sent path. Throughput may vary in quick JMH runs, so the final conclusion must distinguish allocation reduction from directional throughput movement.
