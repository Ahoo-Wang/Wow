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

import com.mongodb.MongoServerUnavailableException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import com.mongodb.MongoWriteException
import com.mongodb.ServerAddress
import com.mongodb.WriteError
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.RecoverableExceptionRegistrar
import me.ahoo.wow.exception.recoverable
import org.bson.BsonDocument
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MongoRecoverableTest {
    @Test
    fun mongoSocketExceptionIsRecoverable() {
        RecoverableExceptionRegistrar.getRecoverableType(MongoSocketException::class.java)
            .assert().isEqualTo(RecoverableType.RECOVERABLE)
        val exception = MongoSocketException("connection refused", ServerAddress("localhost"))
        exception.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun mongoTimeoutExceptionIsRecoverable() {
        val exception = MongoTimeoutException("timeout")
        exception.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @Test
    fun mongoServerUnavailableExceptionIsRecoverable() {
        val exception = MongoServerUnavailableException("server unavailable")
        exception.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
    }

    @ParameterizedTest
    @ValueSource(ints = [6, 7, 89, 91, 133, 189, 262, 264, 10107])
    fun recoverableWriteErrorCodes(code: Int) {
        val error = WriteError(code, "test", BsonDocument())
        error.isRecoverableWriteError().assert().isTrue()
    }

    @Test
    fun duplicateKeyIsNotRecoverableWriteError() {
        val error = WriteError(11000, "duplicate key", BsonDocument())
        error.isRecoverableWriteError().assert().isFalse()
    }

    @Test
    fun recoverableMongoWriteException() {
        val writeException = MongoWriteException(
            WriteError(133, "shard unavailable", BsonDocument()),
            ServerAddress("localhost"),
        )
        val mapped = RecoverableMongoWriteException(writeException)
        mapped.recoverable.assert().isEqualTo(RecoverableType.RECOVERABLE)
        mapped.error.code.assert().isEqualTo(133)
        mapped.cause.assert().isSameAs(writeException)
    }
}
