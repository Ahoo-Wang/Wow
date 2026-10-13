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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class SemanticTypeSchemaTest {
    private val seconds = TimeSpan(TimeUnit.SECONDS)
    private val member = Reference(contextName = "example", aggregateName = "member")
    private val byFields = Reference(contextNameField = "contextName", aggregateNameField = "aggregateName")

    private fun scalar(type: QueryValueType, semantic: QuerySemanticType?) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(type), nullable = false, semanticType = semantic)

    private fun schemaOf(vararg properties: Pair<String, QueryValueSchema>) =
        LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = properties.toMap()))

    @Test
    fun `a duration is a number and a reference an id, its names in siblings when it reads them from there`() {
        schemaOf("timeout" to scalar(QueryValueType.INTEGER, seconds))
        schemaOf("timeout" to scalar(QueryValueType.DECIMAL, seconds))
        schemaOf("memberId" to scalar(QueryValueType.STRING, member))
        schemaOf("memberNo" to scalar(QueryValueType.INTEGER, member))
        schemaOf("productIds" to arrayFixture(scalar(QueryValueType.STRING, member)))
        schemaOf("byKey" to mapFixture(scalar(QueryValueType.INTEGER, seconds)))
        schemaOf(
            "either" to QueryValueSchema(
                QueryValueKind.UNION,
                alternatives = listOf(scalar(QueryValueType.INTEGER, seconds), QueryValueSchema(QueryValueKind.NULL)),
            ),
        )
        schemaOf(
            "source" to objectFixture(
                "contextName" to scalarFixture(),
                "aggregateName" to QueryValueSchema(
                    QueryValueKind.UNION,
                    alternatives = listOf(scalarFixture(), QueryValueSchema(QueryValueKind.NULL)),
                ),
                "aggregateId" to scalar(QueryValueType.STRING, byFields),
            ),
        )
    }

    @Test
    fun `a wrong duration or reference, or an unknown semantic type, is a schema conflict`() {
        listOf(
            arrayOf("timeout" to scalar(QueryValueType.STRING, seconds)),
            arrayOf("timeout" to scalar(QueryValueType.BOOLEAN, seconds)),
            arrayOf(
                "timeout" to QueryValueSchema(QueryValueKind.ARRAY, items = scalarFixture(), semanticType = seconds)
            ),
            arrayOf("memberId" to scalar(QueryValueType.DECIMAL, member)),
            arrayOf("memberId" to scalar(QueryValueType.BOOLEAN, member)),
            // The names a reference reads must be single-valued string siblings.
            arrayOf("aggregateId" to scalar(QueryValueType.STRING, byFields), "aggregateName" to scalarFixture()),
            arrayOf(
                "aggregateId" to scalar(QueryValueType.STRING, byFields),
                "contextName" to scalarFixture(),
                "aggregateName" to arrayFixture(scalarFixture()),
            ),
            arrayOf(
                "aggregateId" to scalar(QueryValueType.STRING, byFields),
                "contextName" to QueryValueSchema(QueryValueKind.NULL),
                "aggregateName" to scalarFixture(),
            ),
            arrayOf("value" to scalar(QueryValueType.STRING, QuerySemanticType.Unknown)),
            arrayOf("value" to mapFixture(scalar(QueryValueType.STRING, QuerySemanticType.Unknown))),
        ).forEach { properties ->
            assertThrows<QuerySchemaConflictException> { schemaOf(*properties) }
        }
    }

    @Test
    fun `the declaration builder declares a duration or a reference, and a field keeps one semantic type`() {
        val declaration = querySchema {
            field("state.timeout") {
                types(QueryValueType.INTEGER)
                semantic(seconds)
            }
            field("state.memberId") {
                types(QueryValueType.STRING)
                semantic(member)
            }
        }
        declaration.fields.getValue(QueryField("state.timeout")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(seconds))
        declaration.fields.getValue(QueryField("state.memberId")).semanticType.assert()
            .isEqualTo(DeclarationValue.Set(member))
        assertThrows<QuerySchemaConflictException> {
            querySchema {
                field("state.timeout") {
                    semantic(seconds)
                    semantic(TimeSpan(TimeUnit.MINUTES))
                }
            }
        }
        assertThrows<QuerySchemaConflictException> {
            querySchema {
                field("state.timeout") {
                    temporalEpoch()
                    temporalFormatted("yyyy-MM-dd")
                }
            }
        }
    }
}
