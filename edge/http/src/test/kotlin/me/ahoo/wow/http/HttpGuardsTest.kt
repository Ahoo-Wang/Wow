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

package me.ahoo.wow.http

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.wire.ScopeHeader
import me.ahoo.wow.http.internal.HttpSignalSender
import org.junit.jupiter.api.Test
import kotlin.time.Duration

/** The guards of the edge's own types: a signal sender's settings, the Wow-Scope separators, a trace of nothing. */
class HttpGuardsTest {
    @Test
    fun `a signal sender should refuse settings that cannot send`() {
        assertThrownBy<IllegalArgumentException> { HttpSignalSender(queueCapacity = 0) }
        assertThrownBy<IllegalArgumentException> { HttpSignalSender(concurrency = 0) }
        assertThrownBy<IllegalArgumentException> { HttpSignalSender(idleTimeout = Duration.ZERO) }
    }

    @Test
    fun `Wow-Scope members should be separated by single commas`() {
        assertThrownBy<IllegalArgumentException> { ScopeHeader.parse("""owner="a" space="b"""") }
        assertThrownBy<IllegalArgumentException> { ScopeHeader.parse("""owner="a",""") }
        assertThrownBy<IllegalArgumentException> { ScopeHeader.parse("""owner="a", """) }
    }

    @Test
    fun `a trace should be empty for a context or an aggregate type the reads do not serve`() {
        val reads = HttpNode().reads
        runBlocking {
            reads.trace(AggregateType(CONTEXT, "unknown").ref(TENANT, "x"), 1, 10, 10).assert().isEmpty()
            reads.trace(AggregateType("other", "cart").ref(TENANT, "x"), 1, 10, 10).assert().isEmpty()
        }
    }
}
