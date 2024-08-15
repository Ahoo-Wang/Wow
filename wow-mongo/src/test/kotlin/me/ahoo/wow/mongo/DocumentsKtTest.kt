package me.ahoo.wow.mongo

import com.fasterxml.jackson.databind.type.TypeFactory
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.bson.Document
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
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

    private val snapshotType = TypeFactory.defaultInstance()
        .constructParametricType(
            MaterializedSnapshot::class.java,
            MOCK_AGGREGATE_METADATA.state.aggregateType
        )

    @Test
    fun toDynamicDocument() {
        val dynamicDocument = snapshotDocument.replacePrimaryKeyToAggregateId().toDynamicDocument()
        assertThat(dynamicDocument.getNestedDocument("state").getValue<String>("id"), equalTo(aggregateId))
    }

    @Test
    fun toSnapshot() {
        val snapshot = snapshotDocument.toSnapshot<MockStateAggregate>()
        assertThat(snapshot.aggregateId.id, equalTo(aggregateId))
    }

    @Test
    fun toSnapshotState() {
        val state = snapshotDocument.toSnapshotState<MockStateAggregate>()
        assertThat(state.id, equalTo(aggregateId))
    }

    @Test
    fun monoToSnapshot() {
        Mono.just(snapshotDocument)
            .toSnapshot<MockStateAggregate>()
            .test().consumeNextWith {
                assertThat(it.aggregateId.id, equalTo(aggregateId))
            }.verifyComplete()
    }

    @Test
    fun monoToSnapshotState() {
        Mono.just(snapshotDocument)
            .toSnapshotState<MockStateAggregate>()
            .test().consumeNextWith {
                assertThat(it.id, equalTo(aggregateId))
            }.verifyComplete()
    }

    @Test
    fun fluxToSnapshot() {
        Flux.just(snapshotDocument)
            .toSnapshot<MockStateAggregate>()
            .test().consumeNextWith {
                assertThat(it.aggregateId.id, equalTo(aggregateId))
            }.verifyComplete()
    }

    @Test
    fun fluxToSnapshotState() {
        Flux.just(snapshotDocument)
            .toSnapshotState<MockStateAggregate>()
            .test().consumeNextWith {
                assertThat(it.id, equalTo(aggregateId))
            }.verifyComplete()
    }

    @Test
    fun toMaterializedSnapshot() {
        val materializedSnapshot = snapshotDocument.toMaterializedSnapshot<MockStateAggregate>(snapshotType)
        assertThat(materializedSnapshot.aggregateId, equalTo(aggregateId))
    }

    @Test
    fun monoToMaterializedSnapshot() {
        Mono.just(snapshotDocument)
            .toMaterializedSnapshot<MockStateAggregate>(snapshotType)
            .test()
            .consumeNextWith {
                assertThat(it.aggregateId, equalTo(aggregateId))
            }.verifyComplete()
    }

    @Test
    fun fluxToMaterializedSnapshot() {
        Flux.just(snapshotDocument)
            .toMaterializedSnapshot<MockStateAggregate>(snapshotType)
            .test()
            .consumeNextWith {
                assertThat(it.aggregateId, equalTo(aggregateId))
            }.verifyComplete()
    }
}
