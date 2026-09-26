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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.descriptor.ConstraintDescriptor
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode

/** The storage facts Elasticsearch declares for the semantic matrix cells it cannot reproduce natively. */
class ElasticsearchStorageFactsTest {
    private val schema = nativeSchema(
        fields = mapOf(
            QueryField("state.name") to nativeBindings(
                QueryField("state.name"),
                QueryCapability.EXACT_MATCH,
                QueryCapability.PRESENCE,
            ),
            QueryField("state.code") to nativeBindings(QueryField("state.code"), QueryCapability.EXACT_MATCH),
        ),
    )

    @Test
    fun `descriptor names the fields whose null or empty value reads as missing`() {
        val constraints = schema.describe(null, null).constraints

        constraints.single { it.type == ConstraintDescriptor.NULL_OR_EMPTY_AS_MISSING }.fields.assert()
            .containsExactly("state.name")
        constraints.single { it.type == ConstraintDescriptor.ARRAY_EQUALITY }.fields.assert().isNull()
    }

    @Test
    fun `admission rejects an array equality operand with a structured violation`() {
        val violation = assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.count(
                EqualFilter(QueryField("state.code"), JsonSerializer.valueToTree<JsonNode>(listOf("a", "b"))),
                schema,
            )
        }.violation

        violation.assert().isEqualTo(QueryViolation.ArrayEquality(QueryField("state.code")))
        requireNotNull(violation).code.assert().isEqualTo(QueryErrorCodes.ARRAY_EQUALITY)
    }
}
