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

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class IndexMappingDriftTest {
    private fun mapping(json: String): TypeMapping = json.byteInputStream().use {
        TypeMapping.of { builder -> builder.withJson(it) }
    }

    private val definition = mapping(
        """
        {"properties":{
          "requestId":{"type":"keyword"},
          "title":{"type":"text","fields":{"raw":{"type":"keyword"}}},
          "body":{"type":"nested","properties":{
            "bodyType":{"type":"keyword"},
            "body":{"type":"object","dynamic":false,"properties":{"audience":{"type":"keyword"}}}
          }}
        }}
        """.trimIndent(),
    )

    @Test
    fun `an index created from the definition has no drift, whatever dynamic mapping added`() {
        // Elasticsearch answers `dynamic` as a string and leaves `type: object` out; both read alike.
        val actual = mapping(
            """
            {"properties":{
              "requestId":{"type":"keyword"},
              "extra":{"type":"keyword","ignore_above":8191},
              "title":{"type":"text","fields":{"raw":{"type":"keyword"}}},
              "body":{"type":"nested","properties":{
                "bodyType":{"type":"keyword"},
                "body":{"dynamic":"false","properties":{"audience":{"type":"keyword"}}}
              }}
            }}
            """.trimIndent(),
        )

        IndexMappingDrift.between(definition, actual).assert().isEmpty()
    }

    @Test
    fun `an index created from the template alone drifts at the paths the definition maps otherwise`() {
        val actual = mapping(
            """
            {"properties":{
              "requestId":{"type":"keyword","ignore_above":256},
              "title":{"type":"text"},
              "body":{"type":"nested","properties":{
                "bodyType":{"type":"keyword"},
                "body":{"type":"object","enabled":false}
              }}
            }}
            """.trimIndent(),
        )

        IndexMappingDrift.between(definition, actual).assert()
            .containsExactly("body.body", "body.body.audience", "requestId", "title.raw")
    }
}
