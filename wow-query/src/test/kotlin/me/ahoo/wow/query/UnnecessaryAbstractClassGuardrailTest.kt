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

import me.ahoo.wow.tck.query.UnnecessaryAbstractClassGuardrail
import org.junit.jupiter.api.Test

class UnnecessaryAbstractClassGuardrailTest {
    @Test
    fun `no abstract class without an abstract member`() {
        UnnecessaryAbstractClassGuardrail(QueryBackend::class, "me.ahoo.wow.query", ALLOWED).assertNone()
    }

    private companion object {
        /** Public base classes that stay abstract so they are subclassed, never instantiated; changing that is API. */
        val ALLOWED = setOf(
            // The snapshot and event-stream gateways' shared base; it implements the whole QueryGateway.
            "me.ahoo.wow.query.AbstractQueryGateway",
            // The nesting half of ProjectionDsl, and the receiver of the public nestedState() extension.
            "me.ahoo.wow.query.dsl.NestedFieldDsl",
        )
    }
}
