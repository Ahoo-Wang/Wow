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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotIndex
import org.junit.jupiter.api.Test
import java.io.StringReader

/** What a mapping says of each field, as the storage facts read it: every field kind and option the walk knows. */
class ElasticsearchIndexMappingTest {
    private fun mapping(json: String): TypeMapping = TypeMapping.of { it.withJson(StringReader(json)) }

    private val rich = mapping(
        """
        {
          "_source": { "includes": ["state.*", "tenantId"], "excludes": ["state.secret*"] },
          "runtime": {
            "total": { "type": "double" },
            "flag": { "type": "boolean" },
            "day": { "type": "date" },
            "address": { "type": "ip" },
            "label": { "type": "keyword" },
            "count": { "type": "long" },
            "point": { "type": "geo_point" },
            "parts": { "type": "composite", "fields": { "a": { "type": "keyword" }, "b": { "type": "geo_point" } } }
          },
          "properties": {
            "tenantId": { "type": "keyword", "null_value": "none", "normalizer": "lowercase" },
            "alias": { "type": "alias", "path": "tenantId" },
            "state": {
              "properties": {
                "text": { "type": "text", "fielddata": true, "fields": { "keyword": { "type": "keyword" } } },
                "plain": { "type": "text" },
                "wild": { "type": "wildcard", "null_value": "x" },
                "flat": { "type": "flattened", "null_value": "x", "doc_values": false },
                "constant": { "type": "constant_keyword", "value": "c" },
                "counted": { "type": "counted_keyword", "index": false },
                "flag": { "type": "boolean", "null_value": false, "index": false },
                "b": { "type": "byte", "null_value": 1 },
                "s": { "type": "short", "null_value": 1 },
                "i": { "type": "integer", "null_value": 1 },
                "l": { "type": "long", "null_value": 1, "doc_values": false },
                "u": { "type": "unsigned_long", "null_value": 1 },
                "h": { "type": "half_float", "null_value": 1.5 },
                "f": { "type": "float", "null_value": 1.5 },
                "d": { "type": "double", "null_value": 1.5 },
                "scaled": { "type": "scaled_float", "scaling_factor": 100, "null_value": 1.5 },
                "tokens": { "type": "token_count", "analyzer": "standard", "null_value": 0 },
                "day": { "type": "date", "null_value": "2020-01-01", "index": false },
                "nanos": { "type": "date_nanos", "null_value": "2020-01-01" },
                "ip": { "type": "ip", "null_value": "127.0.0.1" },
                "geo": { "type": "geo_point", "null_value": "0,0" },
                "pt": { "type": "point", "null_value": "0,0" },
                "range": { "type": "integer_range", "index": false },
                "suggest": { "type": "search_as_you_type" },
                "version": { "type": "version" },
                "secretKey": { "type": "keyword" },
                "off": { "type": "object", "enabled": false, "properties": { "inner": { "type": "keyword" } } },
                "lines": { "type": "nested", "properties": { "sku": { "type": "keyword" } } }
              }
            }
          }
        }
        """,
    )

    @Test
    fun `the walk reads every field kind and option`() {
        val mapping = ElasticsearchIndexMapping.from("index", rich, maxResultWindow = 50, singleShard = true)
        mapping.maxResultWindow.assert().isEqualTo(50)
        mapping.singleShard.assert().isTrue()
        mapping.provisional.assert().isFalse()
        mapping.fullProjectionAvailable.assert().isFalse()
        val fields = mapping.fields
        fields.getValue("tenantId").normalizer.assert().isEqualTo("lowercase")
        fields.getValue("tenantId").nullValue.assert().isNotNull()
        fields.getValue("alias").kind.assert().isEqualTo(Property.Kind.Keyword)
        fields.getValue("state.text").sortable.assert().isTrue()
        fields.getValue("state.text").multiFields.assert().containsExactly("state.text.keyword")
        fields.getValue("state.plain").aggregatable.assert().isFalse()
        fields.getValue("state.flat").sortable.assert().isFalse()
        fields.getValue("state.constant").aggregatable.assert().isTrue()
        fields.getValue("state.counted").indexed.assert().isFalse()
        fields.getValue("state.flag").indexed.assert().isFalse()
        fields.getValue("state.l").sortable.assert().isFalse()
        fields.getValue("state.day").indexed.assert().isFalse()
        fields.getValue("state.range").indexed.assert().isFalse()
        fields.getValue("state.suggest").indexed.assert().isTrue()
        fields.getValue("state.off.inner").enabled.assert().isFalse()
        fields.getValue("state.lines").kind.assert().isEqualTo(Property.Kind.Nested)
        listOf("b", "s", "i", "u", "h", "f", "d", "scaled", "tokens", "nanos", "ip", "geo", "pt", "wild").forEach {
            fields.getValue("state.$it").nullValue.assert().isNotNull()
        }
        listOf("total", "flag", "day", "address", "label", "count", "parts.a").forEach {
            fields.getValue(it).indexed.assert().isTrue()
        }
        fields.containsKey("point").assert().isFalse()
        fields.containsKey("parts.b").assert().isFalse()
        mapping.find("state.flat.any.key")?.kind.assert().isEqualTo(Property.Kind.Flattened)
        mapping.find("state.unknown.key").assert().isNull()
        mapping.sourceAvailable("state.text").assert().isTrue()
        mapping.sourceAvailable("version").assert().isFalse()
        mapping.sourceAvailable("state.secretKey").assert().isFalse()
        mapping.sourceAvailable("state").assert().isFalse()
    }

    @Test
    fun `a disabled source projects nothing`() {
        val mapping = ElasticsearchIndexMapping.from("index", mapping("""{ "_source": { "enabled": false } }"""))
        mapping.fullProjectionAvailable.assert().isFalse()
        mapping.sourceAvailable("state").assert().isFalse()
        mapping.fieldCount.assert().isEqualTo(0)
    }

    @Test
    fun `several indices behind a name share the fields they map alike`() {
        val first = ElasticsearchIndexMapping.from(
            "a",
            mapping("""{"properties":{"x":{"type":"keyword"},"y":{"type":"long"}}}""")
        )
        val second = ElasticsearchIndexMapping.from(
            "b",
            mapping("""{"properties":{"x":{"type":"keyword"},"y":{"type":"double"}}}""")
        )
        val common = ElasticsearchIndexMapping.common("name", mapOf("a" to first, "b" to second))
        common.fields.keys.assert().containsExactly("x")
        common.singleShard.assert().isFalse()
        ElasticsearchIndexMapping.common("name", mapOf("a" to first)).assert().isSameAs(first)
        assertThrownBy<IllegalArgumentException> { ElasticsearchIndexMapping.common("name", emptyMap()) }
        val noSource = ElasticsearchIndexMapping.from("c", mapping("""{ "_source": { "enabled": false } }"""))
        assertThrownBy<IllegalArgumentException> {
            ElasticsearchIndexMapping.common("name", mapOf("a" to first, "c" to noSource))
        }
    }

    @Test
    fun `the snapshot index maps the system fields and leaves the state to its schema`() {
        val mapping = ElasticsearchIndexMapping.from(
            "snapshot",
            ElasticsearchSnapshotIndex.typeMapping(),
            provisional = true
        )
        mapping.provisional.assert().isTrue()
        mapping.fields.getValue("tenantId").kind.assert().isEqualTo(Property.Kind.Keyword)
        mapping.fields.getValue("operator.crossScope").kind.assert().isEqualTo(Property.Kind.Boolean)
        mapping.fields.keys.filter { it.startsWith("state.") }.assert().isEmpty()
    }
}
