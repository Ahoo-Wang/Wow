# Wow Framework Test Patterns

## AggregateSpec Pattern (Aggregate Command Tests)

### Basic Structure

```kotlin
class XxxSpec : AggregateSpec<XxxAggregate, XxxState>({
    // 1. Define test data
    val skuId = SkuId(...)
    fun price(value: String): BigDecimal = BigDecimal(value).setScale(2, RoundingMode.HALF_UP)

    // 2. Define test scenario
    on {
        // Local variables within scenario
        val businessPartnerId = BusinessPartnerId(id = generateGlobalId(), name = "LY")
        val command = SaveXxx(...)

        name("Scenario description")
        whenCommand(command) {
            // 3. Assertions
            expectNoError()                           // No validation errors
            expectEventType(XxxSaved::class)           // Event type
            expectEventBody<XxxSaved> {                // Event body
                field.assert().isEqualTo(expectedValue)
            }
            expectState {                              // State verification
                field.assert().isEqualTo(expectedValue)
            }

            // 4. Branch scenario
            fork(name = "Sub-scenario") {
                val nextCommand = ...
                whenCommand(nextCommand) {
                    expectEventType(...)
                }
            }
        }
    }
})
```

### Common Assertions

```kotlin
expectNoError()                          // Confirm no validation errors
expectEventType(EventClass::class)        // Confirm returned event type
expectEventBody<EventClass> { ... }       // Confirm event body contents
expectState { ... }                       // Confirm aggregate state
```

### Fork Pattern — State Continuation

`fork` sends additional commands on existing aggregate state:

```kotlin
on {
    whenCommand(createCommand) {
        expectEventType(Created::class)
        ref("Created")   // Name this state checkpoint

        fork(name = "Subsequent operation") {
            whenCommand(updateCommand) {
                expectEventType(Updated::class)
            }
        }
    }
}
```

### Cross-block ref/fork Pattern

When sharing state across `on` blocks (e.g., create → disable → operate), use class-level variables + `ref`/`fork`:

```kotlin
class XxxSpec : AggregateSpec<Xxx, XxxState>({
    // Class-level shared variables
    val sharedBusinessPartnerId = BusinessPartnerId(...)

    on("Create cost") {
        whenCommand(createCommand) {
            ref("Created")
        }
    }
    fork("Created", "Operate after disable") {
        whenCommand(removeCommand) {
            expectState { disabled.assert().isEqualTo(true) }
        }
    }
})
```

## SagaSpec Pattern (Saga Event Handling Tests)

### Basic Structure

```kotlin
class XxxSagaSpec : SagaSpec<XxxSaga>({
    // 1. Mock data
    val mockedSkuId = SkuId(...)

    on {
        name("EventName — Scenario description")

        // 2. Mock state aggregate
        val state = mockk<SomeState> {
            every { id } returns someId
            every { field } returns someValue
        }

        // 3. Construct domain event
        val event = SomeEvent(...).toDomainEvent(
            aggregateId = someId,
            tenantId = "LY",
            commandId = generateGlobalId(),
        )

        // 4. Trigger saga
        whenEvent(event = event, state = state) {
            expectNoError()
            expectCommandCount(1)
            expectCommandType(ExpectedCommand::class)
            expectCommandBody<ExpectedCommand> {
                field.assert().isEqualTo(expectedValue)
            }
        }
    }
})
```

### Multi-command Assertions (expectCommandIterator)

When the saga generates multiple commands:

```kotlin
whenEvent(event = event, state = state) {
    expectNoError()
    expectCommandCount(2)
    expectCommandType(CommandA::class, CommandB::class) // or same type
    expectCommandIterator {
        val first = this.nextCommand(CommandType::class)
        val second = this.nextCommand(CommandType::class)

        first.body.field.assert().isEqualTo(value1)
        second.body.field.assert().isEqualTo(value2)
    }
}
```

### No-command Assertion (expectNoCommand)

When the saga should produce no commands:

```kotlin
whenEvent(event = event, state = state) {
    expectNoCommand()
}
```

### Dependency Injection (inject)

When the saga depends on external services (e.g., SnapshotQueryService):

```kotlin
val queryService = mockk<SnapshotQueryService<SomeState>> {
    every { single(any()) } returns materializedSnapshot.toMono()
}

inject {
    register(queryService)
}
```

## Mock State Construction

### Basic mock (mockk)

```kotlin
val state = mockk<PurchaseOrderState> {
    every { id } returns purchaseOrderId
    every { items } returns listOf(item1, item2)
    every { supplierBusinessPartner } returns supplier
}
```

### Real state + MaterializedSnapshot

```kotlin
val realState = SomeState(id).apply {
    field1 = value1
    field2 = value2
}

val materializedSnapshot = mockk<MaterializedSnapshot<SomeState>> {
    every { aggregateId } returns id
    every { state } returns realState
}

val queryService = mockk<SnapshotQueryService<SomeState>> {
    every { single(any()) } returns materializedSnapshot.toMono()
}
```
