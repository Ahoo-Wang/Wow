# Distributed Transactions (Saga)

The _Wow_ framework provides a stateless _Saga_ implementation based on the *Orchestration pattern*, which can be used to handle distributed transactions.

In nearly three years of actual production environment validation, we have found that stateless _Saga_ is sufficient to meet the needs of actual complex scenarios.

## Saga Pattern

The Saga pattern is a method for distributed transaction coordination that updates each service and publishes messages or events through a series of transaction steps. If a step fails, the Saga will execute compensating transactions to offset the previous transactions.

### Choreography

<center>

![Choreography](/images/saga/choreography.png)
</center>

> Image referenced from [A Saga on Sagas](https://learn.microsoft.com/en-us/previous-versions/msp-n-p/jj591569(v=pandp.10)).

The choreography pattern involves participants exchanging events without a centralized control point.
In this approach, each local transaction publishes domain events, triggering local transactions in other services.

**Advantages:**
- Suitable for simple workflows that require few participants and no complex coordination logic.
- No additional service implementation and maintenance required.
- Eliminates the risk of single points of failure, as responsibility is distributed among distributed transaction participants.

**Disadvantages:**
- Workflow complexity may increase when adding new steps, making it difficult to track which distributed transaction participants respond to specific commands.
- Risk of circular dependencies between distributed transaction participants.
- Integration testing is difficult because all services must be run to simulate transactions.

### Orchestration

<center>

![Orchestration](/images/saga/orchestration.png)
</center>

> Image referenced from [A Saga on Sagas](https://learn.microsoft.com/en-us/previous-versions/msp-n-p/jj591569(v=pandp.10)).

The orchestration pattern requires adding a centralized process manager compared to choreography to tell distributed transaction participants which local transactions to execute.
The Saga process manager handles all transactions and tells participants what operations to perform based on events.

**Advantages:**
- Suitable for complex workflows involving many participants or adding new participants over time.
- Greater flexibility in controlling the flow and activity stream for each participant.
- Does not introduce circular dependencies because the process manager unilaterally depends on Saga participants.
- Separation of concerns, as participants do not need to know about other participants' domain events and commands.

**Disadvantages:**
- Requires separate maintenance of the process manager.

The Wow framework implements the _Saga_ pattern using the orchestration pattern.

If you want to learn more about the _Saga_ pattern, you can refer to [A Saga on Sagas](https://learn.microsoft.com/en-us/previous-versions/msp-n-p/jj591569(v=pandp.10)).

## Conventions

_Saga_ completes processing logic by subscribing to events and then returns aggregate commands.

- The process manager (_Saga_) needs to add the `@StatelessSaga` annotation so that the framework can automatically discover it.
- Domain event handler functions need to add the `@OnEvent` annotation, but this annotation is not required. By default, naming it `onEvent` indicates that the function is an event receiver function.
- Domain event handler functions accept parameters of: specific domain events (`Prepared`), domain events (`DomainEvent<Prepared>`).
- Domain event handler function return value types are: `null`, command body (`Prepared`), command builder (`CommandBuilder`), command message (`CommandMessage<Prepared>`).
- Domain event handler functions can return `0-N` aggregate commands, which will be sent to the command bus.

## Transfer Process Manager in Bank Transfer Case

<center>

![Transfer Process Manager in Bank Transfer Case](/images/example/transfer-saga.svg)
</center>

The transfer process manager (`TransferSaga`) is responsible for coordinating transfer events and generating corresponding commands.

- `onEvent(Prepared)`: Subscribes to the transfer prepared event (`Prepared`) and generates the entry command (`Entry`).
- `onEvent(AmountEntered)`: Subscribes to the transfer amount entered event (`AmountEntered`) and generates the confirm transfer command (`Confirm`).
- `onEvent(EntryFailed)`: Subscribes to the transfer entry failed event (`EntryFailed`) and generates the unlock amount command (`UnlockAmount`).

```java
@StatelessSaga
public class TransferSaga {

    Entry onEvent(Prepared prepared, AggregateId aggregateId) {
        return new Entry(prepared.to(), aggregateId.getId(), prepared.amount());
    }

    Confirm onEvent(AmountEntered amountEntered) {
        return new Confirm(amountEntered.sourceId(), amountEntered.amount());
    }

    UnlockAmount onEvent(EntryFailed entryFailed) {
        return new UnlockAmount(entryFailed.sourceId(), entryFailed.amount());
    }
}
```

## Unit Testing

> Using `SagaSpec` for Saga unit testing can effectively reduce the workload of writing unit tests.

> `TransferSaga` Unit Test

```kotlin
class TransferSagaSpec : SagaSpec<TransferSaga>({
   on {
      val prepared = Prepared("to", 1)
      whenEvent(prepared) {
         expectNoError()
         expectCommandType(Entry::class)
         expectCommandBody<Entry> {
            id.assert().isEqualTo(prepared.to)
            amount.assert().isEqualTo(prepared.amount)
         }
      }
   }
   on {
      val amountEntered = AmountEntered("sourceId", 1)
      whenEvent(amountEntered) {
         expectNoError()
         expectCommandType(Confirm::class)
         expectCommandBody<Confirm> {
            id.assert().isEqualTo(amountEntered.sourceId)
            amount.assert().isEqualTo(amountEntered.amount)
         }
      }
   }
   on {
      val entryFailed = EntryFailed("sourceId", 1)
      whenEvent(entryFailed) {
         expectCommandType(UnlockAmount::class)
         expectCommandBody<UnlockAmount> {
            id.assert().isEqualTo(entryFailed.sourceId)
            amount.assert().isEqualTo(entryFailed.amount)
         }
      }
   }
})
```
