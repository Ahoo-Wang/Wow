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

package me.ahoo.wow.compensation.api.query

import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.compensation.api.IRetrySpec
import me.ahoo.wow.compensation.api.RetrySpec
import me.ahoo.wow.compensation.api.RetrySpec.Companion.materialize
import me.ahoo.wow.compensation.api.RetrySpec.Companion.toSpec
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class RetrySpecTest {

    @Test
    fun materializeSelf() {
        val retrySpec = RetrySpec(1, 2, 3)
        val materialized = retrySpec.materialize()
        assertThat(retrySpec, sameInstance(materialized))
    }

    @Test
    fun materialize() {
        val retrySpec: IRetrySpec = object : IRetrySpec {
            override val maxRetries: Int
                get() = 1
            override val minBackoff: Int
                get() = 2
            override val executionTimeout: Int
                get() = 3
        }
        val materialized = retrySpec.materialize()
        assertThat(retrySpec, not(sameInstance(materialized)))
        assertThat(materialized.maxRetries, equalTo(retrySpec.maxRetries))
        assertThat(materialized.minBackoff, equalTo(retrySpec.minBackoff))
        assertThat(materialized.executionTimeout, equalTo(retrySpec.executionTimeout))
    }

    @Test
    fun toSpec() {
        val retry = Retry()
        val retrySpec = retry.toSpec()
        assertThat(retrySpec.maxRetries, equalTo(retry.maxRetries))
        assertThat(retrySpec.minBackoff, equalTo(retry.minBackoff))
        assertThat(retrySpec.executionTimeout, equalTo(retry.executionTimeout))
    }
}
