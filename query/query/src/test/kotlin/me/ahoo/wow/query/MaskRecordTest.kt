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

package me.ahoo.wow.query

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/** The response masks of a point read: [maskRecord] and the record [QueryAdmission.admitRecord] returns. */
class MaskRecordTest {
    private val mask = MaskRule(SensitivityLevel.DISPLAY)

    private fun record(): ObjectNode =
        WowJson.mapper.readTree("""{"aggregateId":"a1","deleted":false,"state":{"name":"n","secret":"abc"}}""")
            as ObjectNode

    private fun masked(rule: MaskRule?) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), maskRule = rule)

    @Test
    fun `maskRecord applies the schema masks and leaves an unmasked schema's record alone`() {
        val masked = boundSchemaFixture(objectFixture("state" to objectFixture("secret" to masked(mask))))
        masked.maskRecord(record())["state"]["secret"].stringValue().assert().isEqualTo("***")
        val plain = boundSchemaFixture(objectFixture("state" to objectFixture("secret" to scalarFixture())))
        val record = record()
        plain.maskRecord(record).assert().isSameAs(record)
        record["state"]["secret"].stringValue().assert().isEqualTo("abc")
    }

    @Test
    fun `a point read resolves aliases and masks the record it admits`() {
        val schema = boundSchemaFixture(
            objectFixture(
                "state" to objectFixture(
                    "name" to QueryValueSchema(
                        kind = QueryValueKind.SCALAR,
                        valueTypes = setOf(QueryValueType.STRING),
                        maskRule = mask,
                        aliases = setOf(QueryField("state.title")),
                    ),
                    "secret" to masked(null),
                ),
            ),
        )
        val scope = QueryScope(declared = EqualFilter(QueryField("state.title"), "n"))
        admitted(schema, scope)!!["state"].run {
            this["name"].stringValue().assert().isEqualTo("*")
            this["secret"].stringValue().assert().isEqualTo("abc")
        }
        // Without the schema the alias names a field the record does not have, and nothing is masked.
        admitted(null, scope).assert().isNull()
        admitted(null, QueryScope.NONE)!!["state"]["name"].stringValue().assert().isEqualTo("n")
    }

    private fun admitted(schema: QueryModelSchema?, scope: QueryScope): ObjectNode? {
        var admitted: ObjectNode? = null
        runTest {
            admitted = QueryAdmission(AggregateType("record", "mask"))
                .admitRecord(IdFilter("a1"), record(), schema, QueryCaller(entry = QueryEntry.HTTP, scope = scope))
        }
        return admitted
    }
}
