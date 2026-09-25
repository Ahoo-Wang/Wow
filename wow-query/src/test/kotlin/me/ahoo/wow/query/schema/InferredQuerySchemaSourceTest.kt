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
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class InferredQuerySchemaSourceTest {
    private val field = QueryField("state")

    @Test
    fun `facts become declarations with query meaning applied`() {
        val declaration = objectFact(
            "name" to stringFact(),
            "created" to QueryTypeFact(QueryValueKind.SCALAR, setOf(QueryValueType.STRING), formats = setOf("date-time")),
            "placedAt" to stringFact(member(QueryTemporal(pattern = "yyyy-MM-dd"))),
            "timestamp" to QueryTypeFact(
                QueryValueKind.SCALAR,
                setOf(QueryValueType.INTEGER),
                member = member(QueryTemporal(unit = TimeUnit.SECONDS), Long::class.java),
            ),
            "secret" to stringFact(member(Sensitive(SensitivityLevel.CONFIDENTIAL))),
            "not.a.segment" to stringFact(),
        ).toDeclaration(field)

        val properties = declaration.properties.valueOr(emptyMap())
        properties.keys.assert().containsExactlyInAnyOrder("name", "created", "placedAt", "timestamp", "secret")
        properties.getValue("created").semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
        properties.getValue("placedAt").semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Temporal.Formatted("yyyy-MM-dd")))
        properties.getValue("timestamp").semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)))
        properties.getValue("secret").maskRule.assert()
            .isEqualTo(DeclarationValue.Set(MaskRule(SensitivityLevel.CONFIDENTIAL)))
        properties.getValue("name").semanticType.assert().isEqualTo(DeclarationValue.Set(null))
    }

    @Test
    fun `sensitive members must not be lost or misplaced`() {
        val sensitive = member(Sensitive(SensitivityLevel.DISPLAY))
        listOf(
            objectFact("bad.name" to stringFact(sensitive)),
            QueryTypeFact(QueryValueKind.UNKNOWN, omitted = listOf(sensitive)),
            objectFact(
                "count" to QueryTypeFact(QueryValueKind.SCALAR, setOf(QueryValueType.INTEGER), member = sensitive)
            ),
            objectFact("typed" to stringFact(member(Sensitive(SensitivityLevel.DISPLAY), Int::class.java))),
            objectFact(
                "twice" to stringFact(
                    QueryMemberFact(
                        "twice",
                        String::class.java,
                        listOf(Sensitive(SensitivityLevel.DISPLAY), Sensitive(SensitivityLevel.CONFIDENTIAL)),
                    ),
                ),
            ),
        ).forEach { fact ->
            assertThrows<QuerySchemaConflictException> { fact.toDeclaration(field) }
        }
    }

    @Test
    fun `snapshot payload leaves presence to the system declaration`() {
        val source = InferredQuerySchemaSource(
            modelSource = { objectFact("name" to stringFact()) },
            typeResolver = { String::class.java },
        )
        val context = QuerySchemaContext(MaterializedNamedAggregate("test", "test"), QueryModel.SNAPSHOT)

        val payload = source.load(context).single().block()!!.fields.getValue(field)

        payload.nullable.assert().isEqualTo(DeclarationValue.Unset)
        payload.required.assert().isEqualTo(DeclarationValue.Unset)
        payload.properties.valueOr(emptyMap()).keys.assert().containsExactly("name")
    }

    private fun member(annotation: Annotation, type: Class<*> = String::class.java) =
        QueryMemberFact("member", type, listOf(annotation))

    private fun stringFact(member: QueryMemberFact? = null) =
        QueryTypeFact(QueryValueKind.SCALAR, setOf(QueryValueType.STRING), nullable = false, member = member)

    private fun objectFact(vararg properties: Pair<String, QueryTypeFact>) = QueryTypeFact(
        QueryValueKind.OBJECT,
        setOf(QueryValueType.OBJECT),
        nullable = false,
        properties = properties.toMap(),
    )
}
