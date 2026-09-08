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
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import kotlin.reflect.jvm.javaField

class QuerySchemaMaskInheritanceTest {
    private val merger = QuerySchemaMerger()

    @Test
    fun `named overlays retain the previously masked dynamic value`() {
        val lower = QueryFieldDeclaration(additionalProperties = DeclarationValue.Set(stringMask()))
        val plain = QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
        listOf(false, true).forEach { dotted ->
            val schema = mergeNamedOverlay(lower, plain, dotted)
            schema.value(
                QueryField("state.values.private").toPathTemplate()
            )!!.maskRule.assert().isEqualTo(fullMaskRule())
            val model = QueryModelSchema(QueryModel.SNAPSHOT, emptySet(), schema, emptyMap(), true)
            val data = JsonSerializer.readTree(
                """{"state":{"values":{"private":"secret","other":"secret"}}}"""
            ) as ObjectNode
            SchemaMasker.create(model)!!.mask(data).assert().isEqualTo(
                JsonSerializer.readTree("""{"state":{"values":{"private":"******","other":"******"}}}""")
            )
        }
    }

    @Test
    fun `named overlays retain masks nested inside dynamic object and array values`() {
        val objectValue = QueryFieldDeclaration(properties = DeclarationValue.Set(mapOf("secret" to stringMask())))
        listOf(objectValue, QueryFieldDeclaration(items = DeclarationValue.Set(objectValue))).forEach { dynamic ->
            val lower = QueryFieldDeclaration(additionalProperties = DeclarationValue.Set(dynamic))
            listOf(false, true).forEach { dotted ->
                val value = mergeNamedOverlay(
                    lower,
                    QueryFieldDeclaration(title = DeclarationValue.Set("Named")),
                    dotted
                )
                    .value(QueryField("state.values.private.secret").toPathTemplate())!!
                value.maskRule.assert().isEqualTo(fullMaskRule())
            }
        }
    }

    @Test
    fun `structure replacements retain nested dynamic masks on newly named keys`() {
        val plain = QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
        val lower = QueryFieldDeclaration(
            additionalProperties = DeclarationValue.Set(
                QueryFieldDeclaration(additionalProperties = DeclarationValue.Set(stringMask()))
            )
        )
        val higher = QueryFieldDeclaration(
            additionalProperties = DeclarationValue.Set(
                QueryFieldDeclaration(
                    additionalProperties = DeclarationValue.Set(plain),
                    properties = DeclarationValue.Set(mapOf("private" to plain)),
                )
            )
        )
        val schema = mergeTrees(lower, higher)
        schema.value(
            QueryField("state.values.any.private").toPathTemplate()
        )!!.maskRule.assert().isEqualTo(fullMaskRule())
    }

    @Test
    fun `named overlays cannot replace protected dynamic strings with other shapes`() {
        val lower = QueryFieldDeclaration(additionalProperties = DeclarationValue.Set(stringMask()))
        val integer = QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        listOf(false, true).forEach { dotted ->
            assertThrows<QuerySchemaConflictException> { mergeNamedOverlay(lower, integer, dotted) }
        }
    }

    @Test
    fun `same priority named and dynamic declarations preserve masks independent of source order`() {
        val dynamic = PrioritizedQuerySchemaDeclaration(
            100,
            QuerySchemaDeclaration(
                mapOf(
                    QueryField("state.values") to QueryFieldDeclaration(additionalProperties = DeclarationValue.Set(stringMask()))
                )
            ),
        )
        val named = PrioritizedQuerySchemaDeclaration(
            100,
            QuerySchemaDeclaration(
                mapOf(
                    QueryField("state.values") to QueryFieldDeclaration(properties = DeclarationValue.Set(mapOf("private" to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING))))))
                )
            ),
        )
        listOf(listOf(dynamic, named), listOf(named, dynamic)).forEach { sources ->
            merger.merge(system(), sources).value(QueryField("state.values.private").toPathTemplate())!!
                .maskRule.assert().isEqualTo(fullMaskRule())
        }
    }

    @Test
    fun `one original declaration can explicitly exclude a named public key from dynamic masking`() {
        val declaration = QueryFieldDeclaration(
            properties = DeclarationValue.Set(
                mapOf(
                    "public" to QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)))
                )
            ),
            additionalProperties = DeclarationValue.Set(stringMask()),
        )
        val schema = mergeTrees(declaration)
        schema.value(QueryField("state.values.public").toPathTemplate())!!.maskRule.assert().isNull()
        schema.value(QueryField("state.values.private").toPathTemplate())!!.maskRule.assert().isEqualTo(fullMaskRule())
    }

    private fun mergeNamedOverlay(
        lower: QueryFieldDeclaration,
        child: QueryFieldDeclaration,
        dotted: Boolean,
    ): LogicalQuerySchema = merger.merge(
        system(),
        listOf(
            PrioritizedQuerySchemaDeclaration(100, QuerySchemaDeclaration(mapOf(QueryField("state.values") to lower))),
            PrioritizedQuerySchemaDeclaration(
                200,
                QuerySchemaDeclaration(
                    if (dotted) {
                        mapOf(QueryField("state.values.private") to child)
                    } else {
                        mapOf(
                            QueryField("state.values") to QueryFieldDeclaration(properties = DeclarationValue.Set(mapOf("private" to child)))
                        )
                    }
                ),
            ),
        ),
    )

    private fun stringMask() = QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
        maskRule = DeclarationValue.Set(fullMaskRule()),
    )

    private fun mergeTrees(vararg declarations: QueryFieldDeclaration): LogicalQuerySchema = merger.merge(
        system(),
        declarations.mapIndexed { index, declaration ->
            PrioritizedQuerySchemaDeclaration(
                index,
                QuerySchemaDeclaration(mapOf(QueryField("state.values") to declaration))
            )
        }
    )

    private fun system(): QuerySchemaDeclaration =
        SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private data class Masked(@field:Mask val secret: String)
}
