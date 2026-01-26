/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.serialization

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.node.ObjectNode
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEvent
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.toMutableDomainEventRecord
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.serialization.event.JsonDomainEvent
import me.ahoo.wow.serialization.event.StateJsonRecord
import me.ahoo.wow.serialization.event.toDomainEventRecord
import me.ahoo.wow.tck.event.MockDomainEventStreams
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import java.time.Clock

internal class JsonSerializerTest {
    @Test
    fun aggregateId() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId()
        val output = aggregateId.toJsonString()
        val input = output.toObject<AggregateId>()
        input.assert().isEqualTo(aggregateId)
    }

    @Test
    fun command() {
        val command =
            MockCreateAggregate(generateGlobalId(), generateGlobalId())
                .toCommandMessage(
                    tenantId = generateGlobalId(),
                    ownerId = generateGlobalId(),
                    spaceId = generateGlobalId()
                )
        val output = command.toJsonString()
        val input = output.toObject<CommandMessage<*>>()
        input.assert().isEqualTo(command)
    }

    @Test
    fun eventStream() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
            ownerId = generateGlobalId(),
            spaceId = generateGlobalId(),
        )

        val output = eventStream.toJsonString()
        val input = output.toObject<DomainEventStream>()
        input.assert().isEqualTo(eventStream)
    }

    @Test
    fun eventStreamCopy() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
            ownerId = generateGlobalId(),
            spaceId = generateGlobalId()
        )
        val copied = eventStream.deepCopy(DomainEventStream::class.java)
        copied.assert().isNotSameAs(eventStream)
        copied.assert().isEqualTo(eventStream)
    }

    @Test
    fun domainEvent() {
        val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
        val domainEvent = MockDomainEventStreams
            .generateEventStream(
                aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
                eventCount = 1,
                ownerId = generateGlobalId(),
                spaceId = generateGlobalId(),
                createdEventSupplier = { MockAggregateCreated(generateGlobalId()) },
            ).first()

        val output = domainEvent.toJsonString()
        val input = output.toObject<DomainEvent<*>>()
        input.assert().isEqualTo(domainEvent)
    }

    @Test
    fun asDomainEventWhenNotFoundClass() {
        val namedAggregate = requiredNamedAggregate<MockAggregateCreated>()
        val mockEvent = MockAggregateCreated(generateGlobalId())
            .toDomainEvent(
                aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
                commandId = generateGlobalId(),
                ownerId = generateGlobalId(),
                spaceId = generateGlobalId()
            )
        val mockEventJson = mockEvent.toJsonString()
        val mutableDomainEventRecord =
            mockEventJson.toObjectNode().toDomainEventRecord().toMutableDomainEventRecord()
        mutableDomainEventRecord.bodyType = "NotFoundClass"
        val failedDomainEvent = mutableDomainEventRecord.actual.toJsonString().toObject<DomainEvent<Any>>()
        failedDomainEvent.assert().isInstanceOf(JsonDomainEvent::class.java)
        val failedJsonDomainEvent = failedDomainEvent as JsonDomainEvent
        failedJsonDomainEvent.id.assert().isEqualTo(mockEvent.id)
        failedJsonDomainEvent.aggregateId.assert().isEqualTo(mockEvent.aggregateId)
        failedJsonDomainEvent.ownerId.assert().isEqualTo(mockEvent.ownerId)
        failedJsonDomainEvent.spaceId.assert().isEqualTo(mockEvent.spaceId)
        failedJsonDomainEvent.bodyType.assert().isEqualTo(mutableDomainEventRecord.bodyType)
        failedJsonDomainEvent.revision.assert().isEqualTo(mutableDomainEventRecord.revision)
        val failedDomainEventJson = failedDomainEvent.toJsonString()
        val failedDomainEventRecord =
            failedDomainEventJson.toJsonNode<ObjectNode>().toDomainEventRecord().toMutableDomainEventRecord()
        failedDomainEventRecord.bodyType.assert().isEqualTo(failedJsonDomainEvent.bodyType)
        failedDomainEventRecord.bodyType = MockAggregateCreated::class.java.name
        val mockEvent2 = failedDomainEventRecord.toDomainEvent()
        mockEvent2.assert().isEqualTo(mockEvent)
    }

    @Test
    fun snapshot() {
        val aggregateMetadata = MOCK_AGGREGATE_METADATA
        val aggregateId = aggregateMetadata.aggregateId(
            generateGlobalId(),
            tenantId = generateGlobalId(),
        )
        val stateAggregate = ConstructorStateAggregateFactory.create(aggregateMetadata.state, aggregateId)
        val snapshot: Snapshot<MockStateAggregate> = SimpleSnapshot(stateAggregate, Clock.systemUTC().millis())
        val output = snapshot.toJsonString()
        val input = output.toObject<Snapshot<*>>()
        input.assert().isEqualTo(snapshot)
    }

    @Test
    fun stateEventStream() {
        val namedAggregate = requiredNamedAggregate<MockCreateAggregate>()
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
            ownerId = generateGlobalId(),
            spaceId = generateGlobalId()
        )
        val stateRoot = MockStateAggregate(eventStream.aggregateId.id)
        val stateEvent = eventStream.toStateEvent(stateRoot)
        val output = stateEvent.toJsonString()
        val input = output.toObject<StateEvent<*>>()
        input.assert().isEqualTo(stateEvent)
    }

    @Test
    fun stateEventStreamIfNotFound() {
        val namedAggregate = "not.found".toNamedAggregate(generateGlobalId())
        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregate.aggregateId(tenantId = generateGlobalId()),
            eventCount = 1,
        )
        val stateRoot = mapOf("id" to generateGlobalId())
        val stateEvent = eventStream.toStateEvent(stateRoot)
        val output = stateEvent.toJsonString()
        val input = output.toObject<StateEvent<*>>()
        input.state.assert().isInstanceOf(StateJsonRecord::class.java)
    }

    @Test
    fun anyToJsonNode() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId()
        val jsonNode = aggregateId.toJsonNode<ObjectNode>()
        val input = jsonNode.toObject<AggregateId>()
        input.assert().isEqualTo(aggregateId)
    }

    @Test
    fun stringToJsonNode() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId()
        val jsonNode = aggregateId.toJsonString().toJsonNode<ObjectNode>()
        val input = jsonNode.toObject<AggregateId>()
        input.assert().isEqualTo(aggregateId)
    }

    @Test
    fun deepCopy() {
        val mutableData = MutableData(generateGlobalId())
        val deepCopied = mutableData.deepCopy()
        mutableData.assert().isNotSameAs(deepCopied)
        mutableData.assert().isEqualTo(deepCopied)
        mutableData.id = generateGlobalId()
        deepCopied.id = generateGlobalId()
        mutableData.assert().isNotEqualTo(deepCopied)
    }

    @Test
    fun toPrettyJson() {
        val data = mapOf("name" to "John", "age" to 30)
        val prettyJson = data.toPrettyJson()
        prettyJson.assert().contains("\n")
        prettyJson.assert().contains("  ")
        val parsed = prettyJson.toObject<Map<String, Any>>()
        parsed["name"].assert().isEqualTo("John")
        parsed["age"].assert().isEqualTo(30)
    }

    @Test
    fun convertWithClass() {
        data class User(
            val name: String,
            val age: Int
        )

        data class UserDto(
            val name: String,
            val age: Int
        )

        val user = User("John", 30)
        val dto = user.convert(UserDto::class.java)
        dto.name.assert().isEqualTo("John")
        dto.age.assert().isEqualTo(30)
    }

    @Test
    fun convertWithJavaType() {
        val json = """{"name":"John","age":30}"""
        val node = json.toJsonNode<ObjectNode>()
        val type = JsonSerializer.typeFactory.constructMapType(HashMap::class.java, String::class.java, Any::class.java)

        @Suppress("UNCHECKED_CAST")
        val map = node.convert<MutableMap<String, Any>>(type)
        map["name"].assert().isEqualTo("John")
        map["age"].assert().isEqualTo(30)
    }

    @Test
    fun convertWithTypeReference() {
        data class Address(
            val city: String,
            val zipCode: String
        )

        data class User(
            val name: String,
            val addresses: List<Address>
        )

        val user = User("John", listOf(Address("Beijing", "100000"), Address("Shanghai", "200000")))
        val typeRef = object : TypeReference<User>() {}
        val converted = user.convert(typeRef)
        converted.name.assert().isEqualTo("John")
        converted.addresses.size
            .assert()
            .isEqualTo(2)
        converted.addresses[0]
            .city
            .assert()
            .isEqualTo("Beijing")
    }

    @Test
    fun convertWithReified() {
        data class User(
            val name: String,
            val age: Int
        )

        data class UserDto(
            val name: String,
            val age: Int
        )

        val user = User("John", 30)
        val dto = user.convert<UserDto>()
        dto.name.assert().isEqualTo("John")
        dto.age.assert().isEqualTo(30)
    }

    @Test
    fun toMap() {
        data class User(
            val name: String,
            val age: Int
        )

        val user = User("John", 30)
        val map = user.toMap()
        map["name"].assert().isEqualTo("John")
        map["age"].assert().isEqualTo(30)
    }

    @Test
    fun toMapWithNestedObject() {
        data class Address(
            val city: String
        )

        data class User(
            val name: String,
            val address: Address
        )

        val user = User("John", Address("Beijing"))
        val map = user.toMap()
        map["name"].assert().isEqualTo("John")
        @Suppress("UNCHECKED_CAST")
        val addressMap = map["address"] as Map<String, Any>
        addressMap["city"].assert().isEqualTo("Beijing")
    }

    @Test
    fun toObjectWithJavaType() {
        val json = """["John","Jane","Bob"]"""
        val type = JsonSerializer.typeFactory.constructCollectionType(List::class.java, String::class.java)
        val list = json.toObject<List<String>>(type)
        list.size.assert().isEqualTo(3)
        list[0].assert().isEqualTo("John")
        list[1].assert().isEqualTo("Jane")
        list[2].assert().isEqualTo("Bob")
    }

    @Test
    fun toObjectWithReified() {
        data class User(
            val name: String,
            val age: Int
        )

        val json = """{"name":"John","age":30}"""
        val user = json.toObject<User>()
        user.name.assert().isEqualTo("John")
        user.age.assert().isEqualTo(30)
    }

    @Test
    fun stringToObjectNode() {
        val json = """{"name":"John","age":30}"""
        val node = json.toObjectNode()
        node["name"].asText().assert().isEqualTo("John")
        node["age"].asInt().assert().isEqualTo(30)
    }

    @Test
    fun jsonNodeToObject() {
        data class User(
            val name: String,
            val age: Int
        )

        val json = """{"name":"John","age":30}"""
        val node = json.toJsonNode<ObjectNode>()
        val user = node.toObject(User::class.java)
        user.name.assert().isEqualTo("John")
        user.age.assert().isEqualTo(30)
    }

    @Test
    fun jsonNodeToObjectWithReified() {
        data class User(
            val name: String,
            val age: Int
        )

        val json = """{"name":"John","age":30}"""
        val node = json.toJsonNode<ObjectNode>()
        val user = node.toObject<User>()
        user.name.assert().isEqualTo("John")
        user.age.assert().isEqualTo(30)
    }
}

data class QueryById(
    val id: String
)

data class MutableData(
    var id: String
)
