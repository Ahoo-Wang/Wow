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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.error.QueryBusyException
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryFailureClassifier
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.spi.subscription.FailureClass
import org.junit.jupiter.api.Test

/** A busy query store is backpressure (D263), an unreachable one systemic (D243); the rest the runtime's. */
class QueryFailureClassifierTest {
    @Test
    fun `a busy store is backpressure, an unreachable one systemic, others unknown to it`() {
        val classifier = QueryFailureClassifier
        classifier.classify(QueryBusyException("views", 4)).assert().isEqualTo(FailureClass.BACKPRESSURE)
        classifier.classify(QueryStorageUnavailableException()).assert().isEqualTo(FailureClass.SYSTEMIC)
        classifier.classify(QueryExecutionException("broken")).assert().isNull()
    }
}
