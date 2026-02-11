package me.ahoo.wow.mongo

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.bson.Document
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class DocumentsKtTest {

    val aggregateId = "0U9Fn5g30000001"
    val snapshotJsonString = """
        {
          "_id": "$aggregateId",
          "contextName": "wow-tck",
          "aggregateName": "mock_aggregate",
          "tenantId": "(0)",
          "version": 1,
          "eventId": "0U9Fn6Ma0000002",
          "firstOperator": "(0)",
          "operator": "(0)",
          "firstEventTime": 1712485610584,
          "eventTime": 1712485610584,
          "state": {
            "id": "$aggregateId"
          },
          "snapshotTime": 1712485610666,
          "deleted": false
        }
    """.trimIndent()

    val snapshotDocument = Document.parse(snapshotJsonString)

    private val snapshotType = JsonSerializer.typeFactory
        .constructParametricType(
            MaterializedSnapshot::class.java,
            MOCK_AGGREGATE_METADATA.state.aggregateType
        )

    @Test
    fun toDynamicDocument() {
        val dynamicDocument = snapshotDocument.replacePrimaryKeyToAggregateId().toDynamicDocument()
        dynamicDocument.getNestedDocument("state").getValue<String>("id").assert().isEqualTo(aggregateId)
    }

    @Test
    fun toSnapshot() {
        val snapshot = snapshotDocument.toSnapshot<MockStateAggregate>()
        snapshot.aggregateId.id.assert().isEqualTo(aggregateId)
    }

    @Test
    fun toSnapshotState() {
        val state = snapshotDocument.toSnapshotState<MockStateAggregate>()
        state.id.assert().isEqualTo(aggregateId)
    }

    @Test
    fun monoToSnapshot() {
        Mono.just(snapshotDocument)
            .toSnapshot<MockStateAggregate>()
            .test().consumeNextWith {
                it.aggregateId.id.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun monoToSnapshotState() {
        Mono.just(snapshotDocument)
            .toSnapshotState<MockStateAggregate>()
            .test().consumeNextWith {
                it.id.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun fluxToSnapshot() {
        Flux.just(snapshotDocument)
            .toSnapshot<MockStateAggregate>()
            .test().consumeNextWith {
                it.aggregateId.id.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun fluxToSnapshotState() {
        Flux.just(snapshotDocument)
            .toSnapshotState<MockStateAggregate>()
            .test().consumeNextWith {
                it.id.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun toMaterializedSnapshot() {
        val materializedSnapshot = snapshotDocument.toMaterializedSnapshot<MockStateAggregate>(snapshotType)
        materializedSnapshot.aggregateId.assert().isEqualTo(aggregateId)
    }

    @Test
    fun monoToMaterializedSnapshot() {
        Mono.just(snapshotDocument)
            .toMaterializedSnapshot<MockStateAggregate>(snapshotType)
            .test()
            .consumeNextWith {
                it.aggregateId.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun fluxToMaterializedSnapshot() {
        Flux.just(snapshotDocument)
            .toMaterializedSnapshot<MockStateAggregate>(snapshotType)
            .test()
            .consumeNextWith {
                it.aggregateId.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }
}
