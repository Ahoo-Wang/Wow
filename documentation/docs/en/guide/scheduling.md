---
title: Scheduled Commands
description: Schedule a command from a decision or a saga, at an instant, after a delay or by cron, and run recurring jobs with @OnSchedule; how they are delivered and tested.
outline: deep
---

# Scheduled Commands

Some things should happen later: an order that cancels itself if it is not paid in time, a reminder, a nightly import. Wow schedules commands as part of a commit, so a schedule is written with the events that set it, or not at all, and it survives restarts and handovers like the log does. This page schedules a command from an aggregate, from a saga, and runs a recurring job.

## When a schedule is due

`ScheduleRule` says when:

| Rule | Is due |
| --- | --- |
| `ScheduleRule.at(instant)` | At an instant |
| `ScheduleRule.after(duration)` | After a delay counted from the commit that sets it |
| `ScheduleRule.cron(expression, zone, misfire)` | Each time a cron expression matches, six fields or a macro such as `@daily`, read in `zone` (UTC by default) |

A schedule belongs to an aggregate, under a key. Setting the key again replaces it, and cancelling it removes it.

## Schedule from a decision

A command function schedules with the `DecideScope`. When the order's last product is confirmed, it schedules its own cancellation once the payment grace period has passed:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#confirm-stock -->

```kotlin
/**
 * Confirms one product. A confirmation the order does not wait for changes nothing: one read again from the
 * stock stream, or one that arrives after another product's shortage cancelled the order (that reservation is
 * released when the inventory hears of the cancellation).
 */
// Decision: the last confirmation produces two events, the item's and the order's.
@OnCommand(
    produces = [OrderStockItemConfirmed::class, OrderStockConfirmed::class],
    schedules = [CancelOrder::class],
)
fun onCommand(command: ConfirmOrderStock, paymentPolicy: PaymentPolicy, scope: DecideScope): Decision {
    val waiting = state.status == OrderStatus.AWAITING_VALIDATION &&
        command.productId in state.products() &&
        command.productId !in state.confirmedProducts
    if (!waiting) {
        return Decision.NoOp
    }
    val confirmed = OrderStockItemConfirmed(command.productId)
    if (state.confirmedProducts + command.productId != state.products()) {
        return Decision.events(confirmed)
    }
    scope.schedule(
        PAYMENT_GRACE_PERIOD,
        ScheduleRule.after(paymentPolicy.gracePeriod),
        CancelOrder(PAYMENT_TIMEOUT)
    )
    return Decision.events(confirmed, OrderStockConfirmed)
}
```

- `scope.schedule(key, rule, command, target)` sends `command` to `target`, this aggregate unless another ref is given. The schedule is committed with the decision's events, as the system event `wow.ScheduleSet`; a decision that only schedules commits just that.
- `scope.cancelSchedule(key)` cancels it. The order cancels `payment-grace-period` when it is paid. A command already sent is not recalled: its target's state guards it.
- The function declares the scheduled commands' classes in `@OnCommand(schedules = [...])`. The compiler cannot read the calls in a function's body, and the Manifest lists them by full type name. The declaration is what the Manifest says; the kernel does not refuse a schedule it leaves out.

## Schedule from a saga

A saga records the schedule in its `SagaContext`, from any of its handlers: `context.schedule(key, rule, request)` asks the request's target to schedule its command for itself, and `context.cancel(key, target)` cancels it. The runtime sends both after the commands the handler returns, in the order the handler called them, as the system commands `wow.schedule.Set` and `wow.schedule.Cancel`. The target must exist; otherwise it rejects the `wow.schedule.Set`, which the saga's `@OnRejected` handler of `SetSchedule` gets. The handler declares the scheduled commands' classes in `@Sends(schedules = [...])`: scheduling a class it does not declare fails the handling for good, and the aggregate parks at once ([Write a Saga](./sagas#schedule-a-command)).

## How a scheduled command is delivered

- It is sent **at least once, never early**, possibly a little late: after a handover or a backlog, and within the timers' poll interval, `wow.timers.poll-interval` (1 second by default).
- It is **decided once**. Its request id is the timer and the slot (`timer#slot`), so sending it again is a duplicate the target recognizes. The target reads which slot it was sent for from `command.schedule`.
- The target **decides it on its state then**. A rejection is a normal guard, not a failure: a cancellation that arrives after the order was paid is rejected, the timer is done, and the rejection goes to no `@OnRejected` handler.
- A **technical failure** to send it is retried, `wow.timers.max-attempts` times (10 by default), then parked under `wow.timers.sends`, where an operator retries or skips it ([Parked aggregates](./failures#parked-aggregates)). A busy target is tried again a second later without spending an attempt ([How a command is retried](./failures#how-a-command-is-retried)).
- It carries the scope of the commit that scheduled it, and that commit as its causation.
- **Deleting an aggregate cancels its pending schedules.** A scheduled command is not part of the wait of the command that scheduled it.

## Run a recurring job

A job that is not about one aggregate, such as a nightly import, is an `@OnSchedule` handler of a saga. It takes a `ScheduleTick` and, if it needs it, the `SagaContext`, and returns the commands it derives, as an `@OnEvent` handler does. Through the context it may also schedule and call. When its return type does not name the commands, it declares them in `@Sends(derives = [...])`, as any saga handler does. Every morning, the inventory receives the suppliers' deliveries of the day before into stock:

<!-- sample: examples/eshop/inventory/inventory-domain/src/main/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/SupplierDeliverySaga.kt#job -->

```kotlin
@Blocking
@OnSchedule(name = "receive-deliveries", cron = "0 0 6 * * *")
fun onSchedule(tick: ScheduleTick): List<CommandRequest<ReceiveStock>> {
    val today = Instant.ofEpochMilli(tick.due).atZone(ZoneOffset.UTC).toLocalDate()
    val days = (tick.skipped + 1 downTo 1).map { today.minusDays(it) }
    return days.flatMap { day -> deliveries.deliveredOn(tick.tenantId, day) }.map { delivery ->
        // The stock of the product, in the job instance's tenant: ReceiveStock names its aggregate type.
        CommandRequest.of(ReceiveStock(delivery.quantity), delivery.productId)
    }
}
```

- **The tick.** `tenantId` is the instance's tenant and `job` the job's name. `slot` is the time that was due, counted from 1 since the instance was last enabled or rescheduled, and `due` when, in epoch milliseconds. `skipped` counts the slots before it that were missed while no node ran the job. `partition` is the instance's log partition, for a `SHARDED` job only.
- **The commands.** A job's commands go to its instance's tenant, as a saga's go to the tenant of its commit: `CommandRequest.of(body, id)` names the aggregate `id` of the type that decides the body, in that tenant, as in the sample, and `targetTenant` sends one to another tenant. Their ids come from the tick's execution, so handling the tick again sends the same requests.
- **The instance.** Each job is kept by the context's built-in aggregate `<context>.schedule`: a `SYSTEM` or `TENANT` instance's id is the job's name, and a `SHARDED` job has one instance per partition. Each time the rule is due it commits a tick, and the job's subscription, `<processor>.<function>` (or the function's `@Name`), hands it to the handler, retried and parked as any subscription is.

`@OnSchedule` sets the job:

| Setting | Means |
| --- | --- |
| `name` | The job's name, unique in its context: letters, digits, `_` and `-` |
| `cron` | Six fields, as Spring's `CronExpression` reads them: `0 0 6 * * *` is 06:00 every day. A macro such as `@daily` is read too |
| `zone` | The zone `cron` is read in, UTC by default |
| `scope` | `SYSTEM`, the default: one instance, in the default tenant, enabled by the host at startup. `TENANT`: one instance per tenant, created by the system command `wow.schedule.Enable` in that tenant; the host never starts one, and each tenant may override the rule, its zone and its misfire. `SHARDED`: one instance per log partition, in the default tenant, enabled by the host at startup and run by the partition's owner for its own partition's aggregates, each ticked once per slot with `tick.partition` set |
| `misfire` | `COALESCE`, the default: of the slots missed while no node ran the job, only the latest runs, and `skipped` counts the others. `CATCH_UP`: each missed slot runs |

The host configures a job under `wow.schedules.<name>`, and only there, without a new build:

```yaml
wow:
  schedules:
    receive-deliveries:
      cron: "0 30 5 * * *"
      retry:
        max-attempts: 3
```

- `cron`, `zone` and `misfire` override the annotation's rule.
- `enabled: false` disables a `SYSTEM` or `SHARDED` job's instances, at every startup.
- `retry.*` and `parked.*` are the retries of the job's subscription, over its handler's `@Retry` and `wow.subscription.*` ([Failures and Retries](./failures#give-a-handler-its-own-budget)). A job's subscription configured under `wow.subscriptions` instead, or settings for a job no handler runs, stop the node at startup.

An instance's own rule comes before the host's: a tenant's of a `TENANT` job, or one an operator set with `reschedule`. It is kept by the instance, so it outlives a restart.

The job's instances also take commands over HTTP, `POST /<context>/wow.schedule/{id}/<command>`: `enable`, `reschedule`, `disable`, the job's name as the id. They are Wow's system commands, so the public port never allows them by default: a service without a command authorizer refuses them (403 `wow.auth.forbidden`), and with one, the authorizer decides. Under CoSec the action is `<context>:wow.schedule.Enable` (and the other job commands) on `schedule/<job>`, which a policy grants to the operators ([Action names](../operate/security#action-names)). The jobs' own commands, the sagas' and the commands ops sends on the internal port are not refused.

## Test it

A test of a decision that schedules expects the schedule with the aggregate's events ([Test Domain Code](./testing#scheduled-commands)). A job is tested with `scheduleSpec`, against the real job aggregate: enable the instance, move the clock, and check the commands its handler derived, with the expectations of a saga's test:

<!-- sample: examples/eshop/inventory/inventory-domain/src/test/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/SupplierDeliverySagaTest.kt#test -->

```kotlin
@Test
fun `the morning job should receive yesterday's deliveries`() = scheduleSpec(
    handlers,
    start = Instant.parse("2026-10-20T00:00:00Z"),
) {
    enable("receive-deliveries")
    advanceTime(6.hours) {
        expectCommands(
            stock.ref(TENANT, "mug") to ReceiveStock(40),
            stock.ref(TENANT, "cap") to ReceiveStock(12),
        )
    }
    asked.assert().containsExactly(LocalDate.parse("2026-10-19"))
}
```

- `advanceTime` moves the clock and hands over the ticks that fell due; it fails when none did, so a wrong rule does not pass quietly. `ticks` lists the ticks handed over.
- `reschedule` and `disable` send the instance's other commands, and `enable` takes a `tenantId`, a `rule` or a `partition` for the other scopes.
- `whenTick` hands the handler a tick of the test's own, without the job aggregate: a slot the clock would take long to reach, or a tick with `skipped` set.
