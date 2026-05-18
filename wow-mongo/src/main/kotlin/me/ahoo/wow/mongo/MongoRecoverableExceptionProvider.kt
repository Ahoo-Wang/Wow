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
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoOperationTimeoutException
import com.mongodb.MongoServerUnavailableException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.exception.RecoverableExceptionProvider
import me.ahoo.wow.exception.RecoverableExceptionRegistrar

/**
 * Registers MongoDB transient/recoverable error codes as [RecoverableType.RECOVERABLE],
 * enabling the framework's retry mechanism to automatically handle these errors.
 *
 * Covered transient error categories:
 * - **Network**: [MongoSocketException], [MongoServerUnavailableException]
 * - **Timeout**: [MongoTimeoutException], [MongoOperationTimeoutException], [MongoExecutionTimeoutException]
 * - **Replica set state change**: [MongoNodeIsRecoveringException], [MongoNotPrimaryException]
 * - **Connection pool**: [MongoConnectionPoolClearedException]
 */
class MongoRecoverableExceptionProvider : RecoverableExceptionProvider {
    override fun register(registrar: RecoverableExceptionRegistrar) {
        registrar.register(MongoSocketException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(MongoTimeoutException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(MongoOperationTimeoutException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(MongoExecutionTimeoutException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(MongoNodeIsRecoveringException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(MongoNotPrimaryException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(MongoServerUnavailableException::class.java, RecoverableType.RECOVERABLE)
        registrar.register(
            MongoConnectionPoolClearedException::class.java,
            RecoverableType.RECOVERABLE
        )
    }
}
