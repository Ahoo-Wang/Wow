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

package me.ahoo.wow.infra.accessor.function.reactive

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SuspendMonoFunctionAccessorTest {
    @Disabled("How to input kotlin.coroutines.Continuation")
    @Test
    fun invoke() {
        val methodAccessor = ::suspendFunction.toMonoFunctionAccessor<SuspendMonoFunctionAccessorTest, String>()
        methodAccessor.assert().isInstanceOf(SuspendMonoFunctionAccessor::class.java)
        methodAccessor.invoke(this).test()
            .expectNext("suspend")
            .verifyComplete()
    }

    @Suppress("FunctionOnlyReturningConstant")
    suspend fun suspendFunction(): String {
        return "suspend"
    }
}
