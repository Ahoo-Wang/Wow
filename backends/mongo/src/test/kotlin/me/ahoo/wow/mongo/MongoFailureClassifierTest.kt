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

import com.mongodb.MongoConnectionPoolClearedException
import com.mongodb.MongoException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSocketOpenException
import com.mongodb.MongoTimeoutException
import com.mongodb.ServerAddress
import com.mongodb.connection.ClusterId
import com.mongodb.connection.ServerId
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import org.bson.BsonDocument
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.ServiceLoader

/** MongoDB's connection failures are systemic (decision D243); the rest it leaves to the runtime. */
class MongoFailureClassifierTest {
    private val address = ServerAddress()

    @Test
    fun `connection failures are systemic, others unknown to it`() {
        val classifier = MongoFailureClassifier()
        listOf(
            MongoSocketOpenException("Exception opening socket", address, IOException("Connection refused")),
            MongoTimeoutException("Timed out while waiting for a server"),
            MongoNotPrimaryException(BsonDocument(), address),
            MongoNodeIsRecoveringException(BsonDocument(), address),
            MongoConnectionPoolClearedException(ServerId(ClusterId(), address), null),
        ).forEach { classifier.classify(it).assert().isEqualTo(FailureClass.SYSTEMIC) }
        classifier.classify(MongoException(WRITE_CONFLICT, "Write conflict")).assert().isNull()
        classifier.classify(IllegalStateException()).assert().isNull()
    }

    @Test
    fun `the runtime finds it on the class path`() {
        ServiceLoader.load(FailureClassifier::class.java).map { it.javaClass.name }.assert()
            .contains(MongoFailureClassifier::class.java.name)
    }

    private companion object {
        const val WRITE_CONFLICT = 112
    }
}
