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

package me.ahoo.wow.elasticsearch

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ElasticsearchAccessPresenceTest {
    @Test
    fun `an attribute is present with a value, a multiple one empty or not, never as a null`() {
        ElasticsearchAccessPresence.of(
            mapOf("department" to "sales", "org" to null, "sharedWith" to emptyList<String>()),
        ).assert().isEqualTo(mapOf("department" to true, "sharedWith" to true))
        ElasticsearchAccessPresence.json(mapOf("level" to 2L, "org" to null)).toString()
            .assert().isEqualTo("""{"level":true}""")
    }

    @Test
    fun `only an attribute directly under access has a marker, and only a marker names one`() {
        ElasticsearchAccessPresence.markerOf("access.sharedWith").assert().isEqualTo("accessPresent.sharedWith")
        listOf("access", "access.", "access.a.b", "state.access.a", "accessVersion").forEach {
            ElasticsearchAccessPresence.markerOf(it).assert().describedAs(it).isNull()
        }
        ElasticsearchAccessPresence.valuesOf("accessPresent.sharedWith").assert().isEqualTo("access.sharedWith")
        listOf("accessPresent", "accessPresent.", "accessPresent.a.b", "access.sharedWith").forEach {
            ElasticsearchAccessPresence.valuesOf(it).assert().describedAs(it).isNull()
        }
    }
}
