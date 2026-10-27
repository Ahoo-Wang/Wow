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

package me.ahoo.wow.query.error

import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier

/**
 * Classifies the failures of a query a subscription handler ran: a store whose bulkhead did not admit it
 * ([QueryBusyException], `wow.query.busy`) is backpressure, retried with backoff while the partition
 * is throttled; a store that is not reachable ([QueryStorageUnavailableException]) is systemic, which
 * pauses the partition. Any other query failure it leaves to the runtime.
 *
 * Found by [java.util.ServiceLoader] (`META-INF/services`) when the query module is on the class path.
 */
internal class QueryFailureClassifier : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? = when (failure) {
        is QueryBusyException -> FailureClass.BACKPRESSURE
        is QueryStorageUnavailableException -> FailureClass.SYSTEMIC
        else -> null
    }
}
