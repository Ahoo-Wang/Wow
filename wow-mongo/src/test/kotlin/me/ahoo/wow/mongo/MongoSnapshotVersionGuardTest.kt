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

import com.mongodb.MongoClientSettings
import me.ahoo.test.asserts.assert
import me.ahoo.wow.serialization.MessageRecords
import org.bson.BsonDocument
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MongoSnapshotVersionGuardTest {

    @Test
    fun `should build a serializable replacement pipeline from the candidate document`() {
        val candidate = Document(Documents.ID_FIELD, "order-1")
            .append(MessageRecords.VERSION, 2)
            .append("marker", "\$literal-sensitive")
        val codecRegistry = MongoClientSettings.getDefaultCodecRegistry()
        val candidateBson = candidate.toBsonDocument(BsonDocument::class.java, codecRegistry)

        val pipeline = versionGuardedSnapshotReplacement(candidate)

        pipeline.assert().hasSize(1)
        val renderedStage = pipeline.single()
            .toBsonDocument(BsonDocument::class.java, codecRegistry)
        renderedStage.isEmpty().assert().isFalse()
        renderedStage.toJson().assert().contains(candidateBson.toJson())
    }

    @Test
    fun `should reject a candidate document without an integer version`() {
        listOf(
            Document(),
            Document(MessageRecords.VERSION, "2"),
        ).forEach { invalidCandidate ->
            val error = assertThrows<IllegalStateException> {
                versionGuardedSnapshotReplacement(invalidCandidate)
            }
            error.message.assert().isEqualTo("Serialized Wow snapshot has no integer version.")
        }
    }
}
