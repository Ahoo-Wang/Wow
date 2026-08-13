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

package me.ahoo.wow.elasticsearch.eventsourcing

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ElasticsearchQueryPresenceEncoderTest {
    @Test
    fun `encode records direct presence and null recursively without mutating source`() {
        val emptyObject = linkedMapOf<String, Any?>()
        val item = linkedMapOf<String, Any?>("sku" to "A", "note" to null)
        val source = linkedMapOf<String, Any?>(
            "title" to "literal",
            "optional" to null,
            "emptyList" to emptyList<Any?>(),
            "emptyObject" to emptyObject,
            "items" to listOf(item),
        )

        val encoded = ElasticsearchQueryPresenceEncoder.encode(source)

        encoded.assert().isEqualTo(
            linkedMapOf(
                "title" to "literal",
                "optional" to null,
                "emptyList" to emptyList<Any?>(),
                "emptyObject" to linkedMapOf(
                    "__wow_query" to linkedMapOf(
                        "present" to emptyList<String>(),
                        "null" to emptyList<String>(),
                    ),
                ),
                "items" to listOf(
                    linkedMapOf(
                        "sku" to "A",
                        "note" to null,
                        "__wow_query" to linkedMapOf(
                            "present" to listOf("sku", "note"),
                            "null" to listOf("note"),
                        ),
                    ),
                ),
                "__wow_query" to linkedMapOf(
                    "present" to listOf("title", "optional", "emptyList", "emptyObject", "items"),
                    "null" to listOf("optional"),
                ),
            ),
        )
        source.containsKey("__wow_query").assert().isFalse()
        emptyObject.containsKey("__wow_query").assert().isFalse()
        item.containsKey("__wow_query").assert().isFalse()
    }

    @Test
    fun `strip removes presence recursively without mutating encoded document`() {
        val source = linkedMapOf<String, Any?>(
            "items" to listOf(linkedMapOf<String, Any?>("note" to null)),
        )
        val encoded = ElasticsearchQueryPresenceEncoder.encode(source)

        ElasticsearchQueryPresenceEncoder.strip(encoded).assert().isEqualTo(source)
        encoded.containsKey("__wow_query").assert().isTrue()
        ((encoded.getValue("items") as List<*>).single() as Map<*, *>).containsKey("__wow_query")
            .assert().isTrue()
    }

    @Test
    fun `reserved namespace collision is rejected at every object level`() {
        listOf(
            linkedMapOf<String, Any?>("__wow_query" to emptyMap<String, Any?>()),
            linkedMapOf<String, Any?>(
                "nested" to linkedMapOf<String, Any?>("__wow_query" to emptyMap<String, Any?>()),
            ),
            linkedMapOf<String, Any?>(
                "items" to listOf(linkedMapOf<String, Any?>("__wow_query" to emptyMap<String, Any?>())),
            ),
        ).forEach { document ->
            assertThrows<IllegalArgumentException> {
                ElasticsearchQueryPresenceEncoder.encode(document)
            }
        }
    }
}
