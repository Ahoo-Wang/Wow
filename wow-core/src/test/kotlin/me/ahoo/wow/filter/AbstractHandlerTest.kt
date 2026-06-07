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

package me.ahoo.wow.filter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class AbstractHandlerTest {

    @Test
    fun `handle delegates to the filter chain`() {
        val handled = mutableListOf<String>()
        val handler = object : AbstractHandler<String>(
            chain = FilterChain {
                handled.add(it)
                Mono.empty()
            },
            errorHandler = LogErrorHandler(),
        ) {}

        StepVerifier.create(handler.handle("context"))
            .verifyComplete()

        handled.assert().isEqualTo(listOf("context"))
    }

    @Test
    fun `handle stores error on accessor context before delegating to error handler`() {
        val error = IllegalStateException("failed")
        val context = ErrorContext()
        val handledErrors = mutableListOf<Throwable>()
        val handler = object : AbstractHandler<ErrorContext>(
            chain = FilterChain { Mono.error(error) },
            errorHandler = ErrorHandler { _, throwable ->
                handledErrors.add(throwable)
                Mono.empty()
            },
        ) {}

        StepVerifier.create(handler.handle(context))
            .verifyComplete()

        context.getError().assert().isSameAs(error)
        handledErrors.assert().isEqualTo(listOf(error))
    }
}

private class ErrorContext : ErrorAccessor {
    private var error: Throwable? = null

    override fun setError(throwable: Throwable) {
        error = throwable
    }

    override fun getError(): Throwable? = error

    override fun clearError() {
        error = null
    }
}
