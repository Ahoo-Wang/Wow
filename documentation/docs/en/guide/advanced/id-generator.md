# ID Generator

The Wow framework's *message ID* and *aggregate root ID* generation relies on the powerful support provided by [CosId](https://github.com/Ahoo-Wang/CosId).

## Global ID Generator

The *global ID generator* is mainly used to generate message IDs (`Command`, `DomainEvent`, `DomainEventStream`).

By default, the *global ID generator* will obtain the ID generator named `cosid` from CosId's *ID generator container* (`IdGeneratorProvider`).

### Customize Global ID Generator via SPI

Developers can customize the global ID generator through the `GlobalIdGeneratorFactory` SPI extension point.

1. Implement the `GlobalIdGeneratorFactory` interface
2. Add the fully qualified class name of the implementation class in the `META-INF/services/me.ahoo.wow.id.GlobalIdGeneratorFactory` file

```kotlin
@Order(ORDER_LAST)
class TestGlobalIdGeneratorFactory : GlobalIdGeneratorFactory {
    companion object {
        private val log = LoggerFactory.getLogger(TestGlobalIdGeneratorFactory::class.java)
        private const val TEST_MACHINE_ID: Int = 1048575
    }

    override fun create(): CosIdGenerator {
        val idGenerator = Radix62CosIdGenerator(TEST_MACHINE_ID)
        val clockSyncCosIdGenerator = ClockSyncCosIdGenerator(idGenerator)
        if (log.isInfoEnabled) {
            log.info("Create - [$clockSyncCosIdGenerator].")
        }
        return clockSyncCosIdGenerator
    }
}
```

## Aggregate ID Generator

The *aggregate ID generator* is mainly used to generate aggregate root IDs.
Users can obtain the corresponding ID generator from CosId's *ID generator container* (`IdGeneratorProvider`) by defining the ID name in the aggregate root metadata.

```kotlin
@BoundedContext(
    name = SERVICE_NAME,
    alias = SERVICE_ALIAS,
    aggregates = [
        Aggregate(
            name = ORDER_AGGREGATE_NAME,
            id = "<You customize the ID name>", // [!code focus]
            packageScopes = [CreateOrder::class]
        ),
    ],
)
object ExampleService {
    const val SERVICE_NAME = "example-service"
    const val SERVICE_ALIAS = "example"
    const val ORDER_AGGREGATE_NAME = "order"
}
```

1. First obtain the ID name from the metadata, if not defined, use the aggregate root name as the ID name.
2. After obtaining the name, get the corresponding ID generator from the *ID generator container*.
3. If not obtained, create a new `Radix62CosIdGenerator` instance that uses the global ID generator's `machineId` as its `machineId`.

### Custom ID Generator

Developers can customize the ID generator through the `AggregateIdGeneratorFactory` SPI extension point.

1. Implement the `AggregateIdGeneratorFactory` interface
2. Add the fully qualified class name of the implementation class in the `META-INF/services/me.ahoo.wow.id.AggregateIdGeneratorFactory` file

```kotlin
@Order(ORDER_LAST)
class CosIdAggregateIdGeneratorFactory(
    private val idProvider: IdGeneratorProvider = DefaultIdGeneratorProvider.INSTANCE
) :
    AggregateIdGeneratorFactory {
    companion object {
        private val log = LoggerFactory.getLogger(CosIdAggregateIdGeneratorFactory::class.java)
    }

    override fun create(namedAggregate: NamedAggregate): IdGenerator {
        val idGenName = MetadataSearcher.metadata
            .contexts[namedAggregate.contextName]
            ?.aggregates
            ?.get(namedAggregate.aggregateName)
            ?.id
            ?: namedAggregate.aggregateName

        val idGeneratorOp = idProvider.get(idGenName)
        if (idGeneratorOp.isPresent) {
            val idGenerator = idGeneratorOp.get()
            if (log.isInfoEnabled) {
                log.info("Create $idGenerator to $namedAggregate from DefaultIdGeneratorProvider[$idGenName].")
            }
            return idGenerator
        }

        val idGenerator = Radix62CosIdGenerator(GlobalIdGenerator.machineId)
        val clockSyncCosIdGenerator = ClockSyncCosIdGenerator(idGenerator)
        if (log.isInfoEnabled) {
            log.info("Create $clockSyncCosIdGenerator to $namedAggregate.")
        }
        return clockSyncCosIdGenerator
    }
}
```