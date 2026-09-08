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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import org.junit.jupiter.api.Test
import kotlin.reflect.jvm.javaField

class QueryFieldProtectionTest {
    @Test
    fun `source aliases are protected without changing native capabilities`() {
        val schema = schema(
            obj("secret" to string(true), "alias" to string(), "public" to string()),
            mapOf("secret" to "stored.secret", "alias" to "stored.secret", "public" to "stored.public"),
        )
        allowed(schema, "secret").assert().isFalse()
        allowed(schema, "alias").assert().isFalse()
        allowed(schema, "public").assert().isTrue()
        schema.field(QueryField("state.alias"))!!.capabilities.contains(QueryCapability.CURSOR_SORT).assert().isTrue()
    }

    @Test
    fun `parent projection alias carries only secret subtree and does not taint siblings`() {
        val state = obj(
            "source" to obj("secret" to string(true), "public" to string()),
            "alias" to obj("secret" to string(), "public" to string())
        )
        val bindings = mapOf(
            path("state.source") to native("stored", "state.source"),
            path("state.alias") to native("stored", "state.alias"),
            path("state.source.secret") to native("stored.secret", "state.source.secret"),
            path("state.source.public") to native("stored.public", "state.source.public"),
            path("state.alias.secret") to native("stored.secret", "state.alias.secret"),
            path("state.alias.public") to native("stored.public", "state.alias.public"),
        )
        val schema = QueryModelSchema(QueryModel.SNAPSHOT, caps, LogicalQuerySchema(obj("state" to state)), bindings)
        isFieldProtected(schema, path("state.alias")).assert().isTrue()
        allowed(schema, "alias.secret").assert().isFalse()
        allowed(schema, "alias.public").assert().isTrue()
    }

    @Test
    fun `same literal in different namespaces remains independent`() {
        val schema = schema(
            obj("secret" to string(true), "public" to string()),
            mapOf("secret" to "stored.secret", "public" to "state.secret")
        )
        allowed(schema, "public").assert().isTrue()
    }

    @Test
    fun `dynamic protected key does not protect an unrelated fixed sibling`() {
        val map = QueryValueSchema(
            QueryValueKind.OBJECT,
            additionalProperties = obj("secret" to string(true), "public" to string())
        )
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            caps,
            LogicalQuerySchema(obj("state" to obj("map" to map))),
            emptyMap()
        )
        isFieldProtected(schema, path("state.map.home.secret")).assert().isTrue()
        isFieldProtected(schema, path("state.map.home.public")).assert().isFalse()
    }

    @Test
    fun `array ancestry prevents cursor admission while scalar remains allowed`() {
        val state = obj(
            "items" to QueryValueSchema(QueryValueKind.ARRAY, items = obj("name" to string())),
            "name" to string()
        )
        val itemPath = QueryPathTemplate(
            listOf(
                QueryPathSegment.Property("state"),
                QueryPathSegment.Property("items"),
                QueryPathSegment.Item,
                QueryPathSegment.Property("name")
            )
        )
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            caps,
            LogicalQuerySchema(obj("state" to state)),
            mapOf(
                itemPath to native("state.items.name", "state.items.name"),
                path("state.name") to native("state.name", "state.name")
            )
        )
        allowed(schema, "items.name").assert().isFalse()
        allowed(schema, "name").assert().isTrue()
    }

    @Test
    fun `source index retains reordered map exclusions without protecting unrelated branches`() {
        val ordinaryMap = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = string())
        val source = QueryValueSchema(
            QueryValueKind.OBJECT,
            properties = mapOf("public" to ordinaryMap),
            additionalProperties = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = string(true)),
        )
        val alias = QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = ordinaryMap)
        val sourcePath =
            QueryPathTemplate(path("state.source").segments + listOf(QueryPathSegment.Key(0), QueryPathSegment.Key(1)))
        val aliasPath =
            QueryPathTemplate(path("state.alias").segments + listOf(QueryPathSegment.Key(0), QueryPathSegment.Key(1)))
        val physical = path("stored").segments
        val sourceBinding = QueryPathTemplate(physical + listOf(QueryPathSegment.Key(0), QueryPathSegment.Key(1)))
        val aliasBinding = QueryPathTemplate(physical + listOf(QueryPathSegment.Key(1), QueryPathSegment.Key(0)))
        val definition =
            LogicalQuerySchema(obj("state" to obj("source" to source, "alias" to alias, "visible" to string())))
        val schema = QueryModelSchema(
            QueryModel.SNAPSHOT,
            caps,
            definition,
            mapOf(
                sourcePath to QueryValueBindings(
                    caps.associateWith {
                        QueryFieldBindingTemplate(sourceBinding, null)
                    },
                    responsePath = sourcePath
                ),
                aliasPath to QueryValueBindings(
                    caps.associateWith {
                        QueryFieldBindingTemplate(aliasBinding, null)
                    },
                    responsePath = aliasPath
                ),
            )
        )
        mapOf(
            "state.source.home.secret" to true,
            "state.source.public.secret" to false,
            "state.alias.secret.home" to true,
            "state.alias.secret.public" to false,
            "state.alias.public.home" to true,
            "state.alias" to true,
            "state.visible" to false,
        ).forEach { (field, protected) -> isFieldProtected(schema, path(field)).assert().isEqualTo(protected) }
    }

    private fun allowed(schema: QueryModelSchema, name: String): Boolean {
        val field = QueryField("state.$name")
        return isCursorFieldAllowed(schema, field, checkNotNull(schema.field(field)))
    }
    private fun schema(state: QueryValueSchema, physical: Map<String, String>) = QueryModelSchema(
        QueryModel.SNAPSHOT,
        caps,
        LogicalQuerySchema(obj("state" to state)),
        physical.map { (name, storage) -> path("state.$name") to native(storage, "state.$name") }.toMap(),
    )
    private fun native(physical: String, response: String) = QueryValueBindings(
        caps.associateWith { QueryFieldBindingTemplate(path(physical), null) },
        projectionPath = path(physical),
        responsePath = path(response),
    )
    private fun path(value: String) = QueryPathTemplate(value.split('.').map(QueryPathSegment::Property))
    private fun obj(
        vararg properties: Pair<String, QueryValueSchema>
    ) = QueryValueSchema(QueryValueKind.OBJECT, properties = mapOf(*properties))
    private fun string(masked: Boolean = false): QueryValueSchema {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return QueryValueSchema(
            QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
            maskRule = if (masked) {
                MaskRule(
                    FullMaskStrategy::class,
                    annotation,
                    FullMaskStrategy.compile(annotation)
                )
            } else {
                null
            }
        )
    }
    private data class Masked(@field:Mask val secret: String)
    private val caps = setOf(QueryCapability.SORT, QueryCapability.CURSOR_SORT)
}
