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

package me.ahoo.wow.query.tck

import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.SupportMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException

class QueryCapabilitiesTest {
    @Test
    fun `a declined feature should skip with the fact and its value`() {
        assertThrows<TestAbortedException> {
            assumeSupported(SupportMode.NONE, "storage.paging.keyset", "keyset pages")
        }.message.assert().isEqualTo(
            "Assumption failed: Skipped: the storage declares storage.paging.keyset = NONE (keyset pages).",
        )
        SupportMode.entries.filter { it != SupportMode.NONE }.forEach { mode ->
            assertDoesNotThrow { assumeSupported(mode, "storage.paging.keyset", "keyset pages") }
        }
    }

    @Test
    fun `an unbound capability should skip naming the field and the capability`() {
        assertThrows<TestAbortedException> {
            assumeBound(null, "state.createdAt", QueryCapability.AGGREGATE_TEMPORAL, "temporal grouping")
        }.message.assert().isEqualTo(
            "Assumption failed: Skipped: the storage binds no AGGREGATE_TEMPORAL to state.createdAt (temporal grouping).",
        )
        assertDoesNotThrow { assumeBound(Any(), "state.createdAt", QueryCapability.AGGREGATE_TEMPORAL, "x") }
    }
}
