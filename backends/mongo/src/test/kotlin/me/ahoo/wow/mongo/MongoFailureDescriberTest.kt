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

import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoCommandException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteConcernException
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import com.mongodb.bulk.BulkWriteError
import com.mongodb.bulk.BulkWriteResult
import com.mongodb.bulk.WriteConcernError
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.subscription.FailureDescriber
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.junit.jupiter.api.Test

/**
 * #6588: a MongoDB driver's failure parks with its code and category, never the server's message, which quotes the
 * key that collided: the probe's duplicate key, as the server wrote it.
 */
class MongoFailureDescriberTest {
    private val server = ServerAddress()
    private val describer = MongoFailureDescriber()

    /** What [failure] is described as; null when it is not. */
    private fun told(failure: Throwable): String? = describer.describe(failure)?.text

    @Test
    fun `the module should register its describer for ServiceLoader, as a node finds it`() {
        java.util.ServiceLoader.load(FailureDescriber::class.java).map { it.javaClass.name }.assert()
            .contains(MongoFailureDescriber::class.java.name)
    }

    @Test
    fun `a write's duplicate key should be its code and category, without the key`() {
        val error = WriteError(11000, DUPLICATE, BsonDocument())
        told(MongoWriteException(error, server, emptySet())).assert()
            .isEqualTo("write error code 11000 duplicate key")
        val bulk = MongoBulkWriteException(
            BulkWriteResult.unacknowledged(),
            listOf(BulkWriteError(11000, DUPLICATE, BsonDocument(), 0)),
            null,
            server,
            emptySet(),
        )
        told(bulk).assert().isEqualTo("write error code 11000 duplicate key")
        // A bulk write that failed on its write concern alone has no write error to describe.
        val concernOnly =
            MongoBulkWriteException(BulkWriteResult.unacknowledged(), emptyList(), null, server, emptySet())
        told(concernOnly).assert().isNull()
    }

    @Test
    fun `a command's and a write concern's failure should be their code and its name`() {
        val response = BsonDocument("ok", BsonInt32(0)).append("code", BsonInt32(11000))
            .append("codeName", BsonString("DuplicateKey")).append("errmsg", BsonString(DUPLICATE))
        told(MongoCommandException(response, server)).assert()
            .isEqualTo("command failed code 11000 'DuplicateKey'")
        val concern = WriteConcernError(64, "WriteConcernFailed", "waiting for alice.smith@example.com", BsonDocument())
        told(MongoWriteConcernException(concern, null, server, emptySet())).assert()
            .isEqualTo("write concern error code 64 'WriteConcernFailed'")
        // A name a description does not take is left out.
        val unnamed = WriteConcernError(64, "has space", "x", BsonDocument())
        val empty = WriteConcernError(64, "", "x", BsonDocument())
        listOf(unnamed, empty).forEach { error ->
            told(MongoWriteConcernException(error, null, server, emptySet())).assert()
                .isEqualTo("write concern error code 64")
        }
    }

    @Test
    fun `another failure of the driver should be its class name alone`() {
        told(MongoTimeoutException("Timed out reading alice.smith@example.com")).assert()
            .isNull()
    }

    private companion object {
        const val DUPLICATE =
            """E11000 duplicate key error collection: probe.readers index: email_1 dup key: { email: "alice.smith@example.com" }"""
    }
}
