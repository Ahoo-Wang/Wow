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
| `ScheduleRule.cron(expression, zone, misfire)` | Each time a six-field cron expression matches, read in `zone` (UTC by default) |

A schedule belongs to an aggregate, under a key. Setting the key again replaces it, and cancelling it removes it.

## Schedule from a decision

A command function schedules with the `DecideScope`. When the order's last product is confirmed, it schedules its own cancellation once the payment grace period has passed:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#confirm-stock -->

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
- The function declares the scheduled commands' classes in `@OnCommand(schedules = [...])`. The compiler cannot read the calls in a function's body, and the Manifest lists them by full type name.

## Schedule from a saga

A saga returns the schedule as a command for its target: `context.schedule(key, rule, target, command)` asks `target` to schedule `command` for itself, and `context.cancel(key, target)` cancels it. The target must exist; otherwise it rejects the request, which the saga's `@OnRejected` handler gets. The handler declares the scheduled commands in `@OnEvent(schedules = [...])`.

## How a scheduled command is delivered

- It is sent **at least once, never early**, possibly a little late: after a handover or a backlog, and within the timers' poll interval, `wow.timers.poll-interval` (1 second by default).
- It is **decided once**. Its request id is the timer and the slot (`timer#slot`), so sending it again is a duplicate the target recognizes. The target reads which slot it was sent for from `command.schedule`.
- The target **decides it on its state then**. A rejection is a normal guard, not a failure: a cancellation that arrives after the order was paid is rejected, the timer is done, and the rejection goes to no `@OnRejected` handler.
- A **technical failure** to send it is retried, `wow.timers.max-attempts` times (10 by default), then parked under `wow.timers`, where an operator resumes or skips it ([Parked aggregates](./failures#parked-aggregates)).
- It carries the scope of the commit that scheduled it, and that commit as its causation.
- **Deleting an aggregate cancels its pending schedules.** A scheduled command is not part of the wait of the command that scheduled it.

## Run a recurring job

A job that is not about one aggregate, such as a nightly import, is an `@OnSchedule` handler of a saga. It takes a `ScheduleTick` and the `JobContext`, and returns the commands it derives. Every morning, the inventory receives the suppliers' deliveries of the day before into stock:

<!-- sample: example/eshop/inventory/inventory-domain/src/main/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/SupplierDeliverySaga.kt#job -->

```kotlin
@Blocking
@OnSchedule(name = "receive-deliveries", cron = "0 0 6 * * *")
fun onSchedule(tick: ScheduleTick, context: JobContext): List<Command<ReceiveStock>> {
    val today = Instant.ofEpochMilli(tick.due).atZone(ZoneOffset.UTC).toLocalDate()
    val days = (tick.skipped + 1 downTo 1).map { today.minusDays(it) }
    return days.flatMap { day -> deliveries.deliveredOn(tick.tenantId, day) }.map { delivery ->
        context.command(delivery.productId, ReceiveStock(delivery.quantity))
    }
}
```

- **The tick.** `tenantId` is the instance's tenant, `slot` the time that was due (from 1) and `due` when, in epoch milliseconds. `skipped` counts the slots before it that were missed while no node ran the job.
- **The commands.** A job's commands name their full ref: the tick belongs to the job, not to a tenant's commit. Their ids come from the tick's execution, so handling the tick again sends the same requests.
- **The instance.** Each job is kept by the context's built-in aggregate `<context>.schedule`, whose id is the job's name. Each time the rule is due it commits a tick, and the job's subscription, `<processor>.<function>`, hands it to the handler, retried and parked as any subscription is.

`@OnSchedule` sets the job:

| Setting | Means |
| --- | --- |
| `name` | The job's name, unique in its context: letters, digits, `_` and `-` |
| `cron` | Six fields, as Spring's `CronExpression` reads them: `0 0 6 * * *` is 06:00 every day |
| `zone` | The zone `cron` is read in, UTC by default |
| `scope` | `SYSTEM`, the default: one instance, enabled by the host at startup. `TENANT`: one instance per tenant, created by the system command `wow.schedule.Enable` in that tenant, each free to override the rule and its zone |
| `sharded` | A `SYSTEM` job run by each partition's owner for its own partition: one instance per log partition, each ticked once per slot, with `tick.partition` set |
| `misfire` | `COALESCE`, the default: of the slots missed while no node ran the job, only the latest runs, and `skipped` counts the others. `CATCH_UP`: each missed slot runs |

The host overrides a job's rule without a new build, with `wow.schedules.<name>.expression`, `zone` and `misfire`, and `wow.schedules.<name>.enabled: false` keeps a `SYSTEM` job's instances disabled at startup. A tenant's own rule of a `TENANT` job comes before the host's.

The job's instances also take commands over HTTP, `POST /<context>/schedule/{id}/wow.schedule.<command>`: `enable`, `reschedule`, `disable`.

## Test it

A test of a decision that schedules expects the schedule with the aggregate's events ([Test Domain Code](./testing#scheduled-commands)). A job is tested with `scheduleSpec`, against the real job aggregate: enable the instance, move the clock, and check the commands its handler derived:

<!-- sample: example/eshop/inventory/inventory-domain/src/test/kotlin/me/ahoo/wow/example/eshop/inventory/domain/stock/SupplierDeliverySagaTest.kt#test -->

```kotlin
@Test
fun `the morning job should receive yesterday's deliveries`() = scheduleSpec(
    handlers,
    context = "eshop-inventory",
    start = Instant.parse("2026-10-20T00:00:00Z"),
) {
    enable("receive-deliveries")
    advanceTime(6.hours) {
        commands.map { it.ref to it.body }.assert().containsExactly(
            stock.ref(TENANT, "mug") to ReceiveStock(40),
            stock.ref(TENANT, "cap") to ReceiveStock(12),
        )
    }
    asked.assert().containsExactly(LocalDate.parse("2026-10-19"))
}
```
