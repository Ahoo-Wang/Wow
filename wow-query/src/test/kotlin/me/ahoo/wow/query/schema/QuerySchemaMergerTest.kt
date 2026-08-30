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
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.KeepMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit
import kotlin.reflect.jvm.javaField

class QuerySchemaMergerTest {
    private val merger = QuerySchemaMerger()

    @Test
    fun `higher priority should merge by leaf without erasing lower values`() {
        val json = declaration("state.createdAt", valueTypes = setOf(QueryValueType.INTEGER))
        val bean = declaration("state.createdAt", semanticType = Temporal.Epoch(TimeUnit.MILLISECONDS))

        val field = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(100, json),
                PrioritizedQuerySchemaDeclaration(300, bean),
            ),
        ).fields.getValue(LogicalField("state.createdAt"))

        field.valueTypes.assert().isEqualTo(setOf(QueryValueType.INTEGER))
        field.semanticType.assert().isEqualTo(Temporal.Epoch(TimeUnit.MILLISECONDS))
    }

    @Test
    fun `higher priority should override the same extension leaf`() {
        val result = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(100, title("JSON")),
                PrioritizedQuerySchemaDeclaration(300, title("Bean")),
            ),
        )

        result.fields.getValue(LogicalField("state.name")).title.assert().isEqualTo("Bean")
    }

    @Test
    fun `same priority different leaf values should conflict`() {
        val exception = assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(300, title("A")),
                    PrioritizedQuerySchemaDeclaration(300, title("B")),
                ),
            )
        }

        exception.errorCode.assert().isEqualTo(QuerySchemaConflictException.ERROR_CODE)
    }

    @Test
    fun `same priority equal leaf values should merge deterministically`() {
        val declaration = title("Name")

        merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(300, declaration),
                PrioritizedQuerySchemaDeclaration(300, declaration),
            ),
        ).fields.getValue(LogicalField("state.name")).title.assert().isEqualTo("Name")
    }

    @Test
    fun `unset extension leaves should materialize defaults`() {
        val field = merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(
                    300,
                    QuerySchemaDeclaration(
                        mapOf(
                            LogicalField("state.name") to QueryFieldDeclaration(),
                        )
                    )
                )
            ),
        ).fields.getValue(LogicalField("state.name"))

        field.title.assert().isNull()
        field.description.assert().isNull()
        field.enumValues.assert().isNull()
        field.valueTypes.assert().isEmpty()
        field.nullable.assert().isTrue()
        field.required.assert().isFalse()
        field.cardinality.assert().isEqualTo(QueryCardinality.SINGLE)
        field.semanticType.assert().isNull()
        field.dynamicChildren.assert().isFalse()
    }

    @Test
    fun `snapshot state descendants should be allowed`() {
        merger.merge(
            system(),
            listOf(PrioritizedQuerySchemaDeclaration(300, title("Name"))),
        ).fields.assert().containsKey(LogicalField("state.name"))
    }

    @Test
    fun `event stream payload descendants should be allowed`() {
        val payload = declaration("body.body.data", valueTypes = setOf(QueryValueType.STRING))

        merger.merge(
            SystemQuerySchemaSource.declaration(QueryModel.EVENT_STREAM),
            listOf(PrioritizedQuerySchemaDeclaration(300, payload)),
        ).fields.assert().containsKey(LogicalField("body.body.data"))
    }

    @Test
    fun `arbitrary snapshot top level extension should conflict`() {
        val exception = assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(
                            mapOf(
                                LogicalField("custom") to QueryFieldDeclaration(title = DeclarationValue.Set("Custom")),
                            )
                        )
                    )
                ),
            )
        }

        exception.errorCode.assert().isEqualTo(QuerySchemaConflictException.ERROR_CODE)
    }

    @Test
    fun `extension should not overwrite a system leaf`() {
        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(
                        300,
                        QuerySchemaDeclaration(
                            mapOf(
                                LogicalField("state") to QueryFieldDeclaration(
                                    cardinality = DeclarationValue.Set(QueryCardinality.MANY),
                                ),
                            )
                        )
                    )
                ),
            )
        }
    }

    @Test
    fun `mask rules should merge only when equal`() {
        val rule = fullMaskRule()
        val masked = declaration("state.secret", setOf(QueryValueType.STRING), maskRule = rule)
        val sameRule = MaskRule(rule.strategyType, rule.annotation, CompiledMask { "different" })

        sameRule.assert().isEqualTo(rule)

        merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(100, declaration("state.secret")),
                PrioritizedQuerySchemaDeclaration(100, masked),
            ),
        ).fields.getValue(LogicalField("state.secret")).maskRule.assert().isEqualTo(rule)

        merger.merge(
            system(),
            listOf(
                PrioritizedQuerySchemaDeclaration(100, masked),
                PrioritizedQuerySchemaDeclaration(
                    100,
                    declaration("state.secret", setOf(QueryValueType.STRING), maskRule = sameRule),
                ),
            ),
        ).fields.getValue(LogicalField("state.secret")).maskRule.assert().isEqualTo(rule)

        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(100, masked),
                    PrioritizedQuerySchemaDeclaration(200, declaration("state.secret", maskRule = keepMaskRule())),
                ),
            )
        }
    }

    @Test
    fun `materialized masked fields should remain string typed`() {
        val masked = declaration("state.secret", setOf(QueryValueType.STRING), maskRule = fullMaskRule())

        assertThrows<QuerySchemaConflictException> {
            merger.merge(
                system(),
                listOf(
                    PrioritizedQuerySchemaDeclaration(100, masked),
                    PrioritizedQuerySchemaDeclaration(
                        200,
                        declaration("state.secret", setOf(QueryValueType.INTEGER)),
                    ),
                ),
            )
        }
    }

    private fun system(): QuerySchemaDeclaration =
        SystemQuerySchemaSource.declaration(QueryModel.SNAPSHOT)

    private fun declaration(
        field: String,
        valueTypes: Set<QueryValueType>? = null,
        semanticType: QuerySemanticType? = null,
        maskRule: MaskRule? = null,
    ): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(
                LogicalField(field) to QueryFieldDeclaration(
                    valueTypes = valueTypes?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
                    semanticType = semanticType?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
                    maskRule = maskRule?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
                ),
            ),
        )

    private fun fullMaskRule(): MaskRule {
        val annotation = Masked::secret.javaField!!.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private fun keepMaskRule(): MaskRule {
        val annotation = Masked::keep.javaField!!.getAnnotation(KeepMask::class.java)
        return MaskRule(KeepMaskStrategy::class, annotation, KeepMaskStrategy.compile(annotation))
    }

    private fun title(value: String): QuerySchemaDeclaration =
        QuerySchemaDeclaration(
            mapOf(LogicalField("state.name") to QueryFieldDeclaration(title = DeclarationValue.Set(value))),
        )

    private data class Masked(
        @field:Mask val secret: String,
        @field:KeepMask val keep: String,
    )
}
