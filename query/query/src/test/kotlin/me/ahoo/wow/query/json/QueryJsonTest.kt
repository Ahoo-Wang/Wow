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

package me.ahoo.wow.query.json

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class QueryJsonTest {
    @Test
    fun `the module should give a WowJson builder the query wire`() {
        val mapper: JsonMapper = WowJson.builder().addModule(QueryJson.module).build()

        QueryJsonGoldenCases.all().forEach { (name, value) ->
            mapper.writeValueAsString(value).assert().`as`(name).isEqualTo(QueryJson.mapper.writeValueAsString(value))
        }
        mapper.readValue("""{"field":"amount"}""", AggregationExpression::class.java)
            .assert().isEqualTo(AggregationExpression.Field(QueryField("amount")))
    }

    @Test
    fun `the mapper should keep the WowJson modules and add the query module`() {
        QueryJson.mapper.registeredModules().map { it.registrationId.toString() }
            .assert().contains(QueryJson.module.registrationId.toString())
    }

    @Test
    fun `the metric filter exclusion should match only MATCH_ALL`() {
        val exclusion = MatchAllFilterExclusion()

        (exclusion == MatchAllFilterExclusion()).assert().isFalse()
        exclusion.equals(MatchAllFilter).assert().isTrue()
        exclusion.equals(MatchNoneFilter).assert().isFalse()
        exclusion.hashCode().assert().isEqualTo(MatchAllFilter.hashCode())
    }
}
