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
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier

/**
 * Classifies MongoDB's connection failures as systemic (decision D243), for a handler that writes to MongoDB itself:
 * a socket that failed or timed out, no server selected in time, a pool cleared after a failure, a primary that stepped
 * down or a node recovering. Every aggregate of the partition would fail the same way, so the subscription pauses it.
 * A transaction's transient error (a write conflict) stays the aggregate's own, retried within its budget.
 *
 * Found by [java.util.ServiceLoader] (`META-INF/services`) when this backend is on the class path.
 */
internal class MongoFailureClassifier : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? = when (failure) {
        is MongoSocketException,
        is MongoTimeoutException,
        is MongoConnectionPoolClearedException,
        is MongoNotPrimaryException,
        is MongoNodeIsRecoveringException,
        -> FailureClass.SYSTEMIC
        else -> null
    }
}
