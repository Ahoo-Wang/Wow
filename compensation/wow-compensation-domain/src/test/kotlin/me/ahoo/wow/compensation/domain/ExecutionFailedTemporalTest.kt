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

package me.ahoo.wow.compensation.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryTemporal
import me.ahoo.wow.compensation.api.RetryState
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ExecutionFailedTemporalTest {
    @Test
    fun `execution and retry timestamps declare millisecond query semantics`() {
        val timestampFields = listOf(
            ExecutionFailedState::class.java.getDeclaredField("executeAt"),
            RetryState::class.java.getDeclaredField("retryAt"),
            RetryState::class.java.getDeclaredField("timeoutAt"),
            RetryState::class.java.getDeclaredField("nextRetryAt"),
        )
        timestampFields.forEach { field ->
            field.getAnnotation(QueryTemporal::class.java)?.timeUnit
                .assert().isEqualTo(TimeUnit.MILLISECONDS)
        }
    }
}
