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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The inline schemas of a service route's body and answer: a reference, an array of one, a nullable one. */
class InlineSchemasTest {
    @Test
    fun `a nullable inline schema should accept null and keep its references`() {
        val item = Manifest.refSchema("sample.Item")
        val nullableItem = Manifest.nullableSchema(item)
        nullableItem.assert().isEqualTo(mapOf("anyOf" to listOf(mapOf("type" to "null"), item)))
        Manifest.nullableSchema(Manifest.arraySchema(nullableItem)).assert()
            .isEqualTo(mapOf("type" to listOf("array", "null"), "items" to nullableItem))
        // A schema of any value already accepts null.
        Manifest.nullableSchema(mapOf("type" to listOf("object", "null"))).assert()
            .isEqualTo(mapOf("type" to listOf("object", "null")))
        Manifest.refsOf(Manifest.arraySchema(nullableItem)).assert().containsExactly("#/\$defs/sample.Item")
        Manifest.refsOf(nullableItem).assert().containsExactly("#/\$defs/sample.Item")
    }
}
