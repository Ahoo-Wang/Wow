# Projection Processor

The *projection processor* is a mechanism that organizes and stores the state of the domain model in a form that meets query requirements.

Since the _EventStore_ stores aggregate root domain event streams, which are very unfriendly for queries.
By combining the _CQRS_ architectural pattern, separating the *read model* and *write model*, allowing specialized optimization for query requirements.

In the _Wow_ framework, *read model projections* are implemented by defining *projection processors*. Projection processors are responsible for processing domain events and updating the index state of the read model to reflect the latest data state.

:::tip
It should be particularly noted that when the snapshot mode is set to `all`, projections are not necessary.

In general scenarios, the latest state snapshot of the aggregate root can be used as the read model, for example, the [event compensation console](./event-compensation) does not define a projection processor and directly uses the latest state snapshot as the read model.
:::

- Projection processors need to be marked with the `@ProjectionProcessor` annotation so that the framework can automatically discover them.
- Domain event handler functions need to add the `@OnEvent` annotation, but this annotation is not required. By default, naming it `onEvent` indicates that the function is a domain event handler function.
- Domain event handler functions accept parameters of: specific domain events (`OrderCreated`), domain events (`DomainEvent<OrderCreated>`).

```kotlin
@ProjectionProcessor
class OrderProjector {

    fun onEvent(orderCreated: OrderCreated) {
        // Update read model based on domain events
    }
}
```