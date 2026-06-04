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

package me.ahoo.wow.mongo

import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import org.bson.BsonDocument
import org.junit.jupiter.api.Test

class ErrorMappingTest {
    private val namedAggregate = MaterializedNamedAggregate("testContext", "testAggregate")
    private val aggregateId = namedAggregate.aggregateId(generateGlobalId())
    private val requestId = generateGlobalId()

    private fun mockEventStream(): DomainEventStream = mockk(relaxed = true) {
        every { this@mockk.aggregateId } returns this@ErrorMappingTest.aggregateId
        every { requestId } returns this@ErrorMappingTest.requestId
    }

    @Test
    fun `duplicate key with aggregateId and version index maps to EventVersionConflictException`() {
        val eventStream = mockEventStream()
        val writeException = MongoWriteException(
            WriteError(
                11000,
                "duplicate key - ${AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME}",
                BsonDocument()
            ),
            ServerAddress("localhost"),
        )
        val result = writeException.toWowError(eventStream)
        result.assert().isInstanceOf(EventVersionConflictException::class.java)
        (result as EventVersionConflictException).cause.assert().isSameAs(writeException)
    }

    @Test
    fun `duplicate key with requestId index maps to DuplicateRequestIdException`() {
        val eventStream = mockEventStream()
        val writeException = MongoWriteException(
            WriteError(
                11000,
                "duplicate key - ${AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME}",
                BsonDocument()
            ),
            ServerAddress("localhost"),
        )
        val result = writeException.toWowError(eventStream)
        result.assert().isInstanceOf(DuplicateRequestIdException::class.java)
        val ex = result as DuplicateRequestIdException
        ex.aggregateId.assert().isEqualTo(this.aggregateId)
        ex.requestId.assert().isEqualTo(this.requestId)
        ex.cause.assert().isSameAs(writeException)
    }

    @Test
    fun `duplicate key with unknown index returns original exception`() {
        val eventStream = mockEventStream()
        val writeException = MongoWriteException(
            WriteError(11000, "duplicate key - unknown_index", BsonDocument()),
            ServerAddress("localhost"),
        )
        val result = writeException.toWowError(eventStream)
        result.assert().isSameAs(writeException)
    }

    @Test
    fun `recoverable write error maps to RecoverableMongoWriteException`() {
        val eventStream = mockEventStream()
        val writeException = MongoWriteException(
            WriteError(10107, "NotWritablePrimary", BsonDocument()),
            ServerAddress("localhost"),
        )
        val result = writeException.toWowError(eventStream)
        result.assert().isInstanceOf(RecoverableMongoWriteException::class.java)
        (result as RecoverableMongoWriteException).error.code.assert().isEqualTo(10107)
    }

    @Test
    fun `non-recoverable non-duplicate write error returns original exception`() {
        val eventStream = mockEventStream()
        val writeException = MongoWriteException(
            WriteError(50, "SomeError", BsonDocument()),
            ServerAddress("localhost"),
        )
        val result = writeException.toWowError(eventStream)
        result.assert().isSameAs(writeException)
    }
}
