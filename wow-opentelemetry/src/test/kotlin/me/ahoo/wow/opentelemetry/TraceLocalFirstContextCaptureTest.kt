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

package me.ahoo.wow.opentelemetry

import io.opentelemetry.context.ContextKey
import me.ahoo.test.asserts.assert
import me.ahoo.wow.messaging.LocalFirstContextCaptures
import org.junit.jupiter.api.Test
import reactor.util.context.Context

class TraceLocalFirstContextCaptureTest {
    private val marker = ContextKey.named<String>("marker")

    @Test
    fun `the sender's trace context is kept and everything else dropped`() {
        val trace = io.opentelemetry.context.Context.root().with(marker, "parent-1")
        val sender = ReactorTraceContext.set(Context.of("request", "web-request"), trace)

        val captured = LocalFirstContextCaptures.capture(sender)

        ReactorTraceContext.get(captured).get(marker).assert().isEqualTo("parent-1")
        captured.hasKey("request").assert().isFalse()
    }

    @Test
    fun `without a trace in the sender's context the current one is kept`() {
        val trace = io.opentelemetry.context.Context.root().with(marker, "current-1")

        val captured = trace.makeCurrent().use { TraceLocalFirstContextCapture().capture(Context.empty()) }

        ReactorTraceContext.get(captured).get(marker).assert().isEqualTo("current-1")
    }
}
