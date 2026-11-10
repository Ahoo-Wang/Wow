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

import com.mongodb.client.FindIterable
import com.mongodb.client.ListCollectionNamesIterable
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import org.bson.Document
import org.bson.conversions.Bson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Recording the format of a structure whose collection exists already, with or without another node's claim. */
class MongoFormatTest {
    private val formats = mockk<MongoCollection<Document>>(relaxed = true)

    /** A database where the collection `wow_log__shop` exists, and its format record reads as [records] say in turn. */
    private fun database(vararg records: Document?): MongoDatabase {
        val reads = mockk<FindIterable<Document>> { every { first() } returnsMany records.toList() }
        every { formats.find(any<Bson>()) } returns reads
        val names = mockk<ListCollectionNamesIterable>()
        every { names.filter(any()) } returns names
        every { names.first() } returns STRUCTURE
        return mockk {
            every { getCollection(MongoFormat.COLLECTION) } returns formats
            every { listCollectionNames() } returns names
        }
    }

    @Test
    fun `a collection a concurrent claim created after the first read should be recorded, not refused`() {
        // No record at the first read; the collection exists by the check, and so does the claim's record.
        MongoFormat.record(database(null, Document("_id", STRUCTURE).append("version", 2)), STRUCTURE, 2)
        verify(exactly = 1) { formats.insertOne(any()) }
    }

    @Test
    fun `a collection that exists without a record should be refused, and nothing recorded`() {
        assertThrows<IllegalStateException> { MongoFormat.record(database(null, null), STRUCTURE, 2) }
            .message.assert().startsWith("$STRUCTURE exists without a format record")
        verify(exactly = 0) { formats.insertOne(any()) }
    }

    private companion object {
        const val STRUCTURE = "wow_log__shop"
    }
}
