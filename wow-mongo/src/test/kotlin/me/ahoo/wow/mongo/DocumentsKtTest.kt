package me.ahoo.wow.mongo

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.bson.Document
import org.bson.types.Binary
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import tools.jackson.core.exc.StreamWriteException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.util.Date
import java.util.UUID

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
    fun `document should normalize complete bson corpus to canonical object node`() {
        val normalizedDocument = Document(
            mapOf(
                "_id" to ObjectId("64b64c000000000000000001"),
                "objectId" to ObjectId("64b64c000000000000000002"),
                "decimal" to Decimal128(BigDecimal("123.45")),
                "date" to Date(1_700_000_000_000),
                "uuid" to UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "binary" to Binary(byteArrayOf(1, 2, 3)),
                "nullable" to null,
                "nested" to Document(
                    "items",
                    listOf(Document("value", Decimal128(BigDecimal("2.5")))),
                ),
            ),
        ).apply {
            this["_id"] = getObjectId("_id").toHexString()
        }.replacePrimaryKeyToAggregateId()

        val node = normalizedDocument.toObjectNode()

        node.isObject.assert().isTrue()
        node.allNodes().none { it.isPojo }.assert().isTrue()
        JsonSerializer.writeValueAsString(node).assert().isEqualTo(
            JsonSerializer.writeValueAsString(
                JsonSerializer.readTree(JsonSerializer.writeValueAsBytes(normalizedDocument)),
            ),
        )
    }

    private fun JsonNode.allNodes(): Sequence<JsonNode> = sequence {
        yield(this@allNodes)
        this@allNodes.forEach { yieldAll(it.allNodes()) }
    }

    @Test
    fun `direct document tree conversion should reproduce Decimal128 stream write failure`() {
        val document = Document("decimal", Decimal128(BigDecimal("123.45")))

        assertThrows<StreamWriteException> {
            JsonSerializer.valueToTree<ObjectNode>(document)
        }
    }

    @Test
    fun `should convert document to snapshot`() {
        val snapshot = snapshotDocument.toSnapshot<MockStateAggregate>()
        snapshot.aggregateId.id.assert().isEqualTo(aggregateId)
    }

    @Test
    fun `should convert document to snapshot state`() {
        val state = snapshotDocument.toSnapshotState<MockStateAggregate>()
        state.id.assert().isEqualTo(aggregateId)
    }

    @Test
    fun `should convert mono to snapshot`() {
        Mono.just(snapshotDocument)
            .toSnapshot<MockStateAggregate>()
            .test().consumeNextWith {
                it.aggregateId.id.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun `should convert mono to snapshot state`() {
        Mono.just(snapshotDocument)
            .toSnapshotState<MockStateAggregate>()
            .test().consumeNextWith {
                it.id.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun `should convert flux to snapshot`() {
        Flux.just(snapshotDocument)
            .toSnapshot<MockStateAggregate>()
            .test().consumeNextWith {
                it.aggregateId.id.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun `should convert flux to snapshot state`() {
        Flux.just(snapshotDocument)
            .toSnapshotState<MockStateAggregate>()
            .test().consumeNextWith {
                it.id.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun `should convert document to materialized snapshot`() {
        val materializedSnapshot = snapshotDocument.toMaterializedSnapshot<MockStateAggregate>(snapshotType)
        materializedSnapshot.aggregateId.assert().isEqualTo(aggregateId)
    }

    @Test
    fun `should convert mono to materialized snapshot`() {
        Mono.just(snapshotDocument)
            .toMaterializedSnapshot<MockStateAggregate>(snapshotType)
            .test()
            .consumeNextWith {
                it.aggregateId.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }

    @Test
    fun `should convert flux to materialized snapshot`() {
        Flux.just(snapshotDocument)
            .toMaterializedSnapshot<MockStateAggregate>(snapshotType)
            .test()
            .consumeNextWith {
                it.aggregateId.assert().isEqualTo(aggregateId)
            }.verifyComplete()
    }
}
