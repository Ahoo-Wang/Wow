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
package me.ahoo.wow.api.query.schema

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.readValue
import java.util.concurrent.TimeUnit

class QuerySemanticTypeTest {
    private val jsonMapper = jsonMapper()

    private fun read(json: String): QuerySemanticType = jsonMapper.readValue(json, QuerySemanticType::class.java)

    @Test
    fun `duration and reference round trip through semantic JSON`() {
        listOf(
            TimeSpan(TimeUnit.SECONDS),
            Reference(contextName = "example", aggregateName = "member"),
            Reference(contextNameField = "contextName", aggregateNameField = "aggregateName"),
        ).forEach { semantic ->
            read(jsonMapper.writeValueAsString(semantic)).assert().isEqualTo(semantic)
        }
        jsonMapper.writeValueAsString(TimeSpan(TimeUnit.SECONDS)).assert()
            .isEqualTo("""{"type":"DURATION","timeUnit":"SECONDS"}""")
        jsonMapper.writeValueAsString(Reference(contextName = "example", aggregateName = "member")).assert()
            .isEqualTo("""{"type":"REFERENCE","contextName":"example","aggregateName":"member"}""")
    }

    @Test
    fun `a reference names its aggregate or the sibling fields that hold it, never both`() {
        assertThrows<IllegalArgumentException> { Reference() }
        assertThrows<IllegalArgumentException> { Reference(aggregateName = "member") }
        assertThrows<IllegalArgumentException> { Reference(contextNameField = "contextName") }
        assertThrows<IllegalArgumentException> {
            Reference("example", "member", "contextName", "aggregateName")
        }
        assertThrows<IllegalArgumentException> { Reference(contextName = "example", aggregateName = "a member") }
        assertThrows<IllegalArgumentException> {
            Reference(contextNameField = "id.contextName", aggregateNameField = "aggregateName")
        }
        Reference(contextName = "example-service", aggregateName = "execution_failed").aggregateName.assert()
            .isEqualTo("execution_failed")
    }

    @Test
    fun `a type this version does not know reads as unknown`() {
        read("""{"type":"FROM_A_NEWER_SERVER","unit":"SECONDS","nested":{"a":1}}""").assert()
            .isEqualTo(QuerySemanticType.Unknown)
        jsonMapper.readValue<List<QuerySemanticType>>(
            """[{"type":"TEMPORAL_DATE"},{"type":"RATIO","scale":1},{"type":"DECIMAL","scale":2}]""",
        ).assert().containsExactly(Temporal.Date, QuerySemanticType.Unknown, NumericFormat.Decimal(2))
    }
}
