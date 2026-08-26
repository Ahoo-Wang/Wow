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
import java.util.concurrent.TimeUnit

class QuerySchemaTypesTest {
    private val jsonMapper = jsonMapper()

    @Test
    fun `schema identifiers must be safe single segments`() {
        QueryModel("SNAPSHOT").assert().isEqualTo(QueryModel.SNAPSHOT)
        QueryCapability("EXACT_MATCH").assert().isEqualTo(QueryCapability.EXACT_MATCH)
        QueryValueType("STRING").assert().isEqualTo(QueryValueType.STRING)

        assertThrows<IllegalArgumentException> { QueryModel("../snapshot") }
        assertThrows<IllegalArgumentException> { QueryCapability("FULL.TEXT") }
        assertThrows<IllegalArgumentException> { QueryValueType("/string") }
    }

    @Test
    fun `temporal variants should round trip through semantic JSON`() {
        val semantics = listOf(
            Temporal.Date to "TEMPORAL_DATE",
            Temporal.Epoch(TimeUnit.SECONDS) to "TEMPORAL_EPOCH",
            Temporal.Formatted("yyyy-MM-dd") to "TEMPORAL_FORMATTED",
        )

        semantics.forEach { (semantic, type) ->
            val json = jsonMapper.writeValueAsString(semantic)

            json.assert().contains(type)
            jsonMapper.readValue(json, QuerySemanticType::class.java).assert().isEqualTo(semantic)
        }
    }

    @Test
    fun `formatted temporal requires a usable pattern`() {
        assertThrows<IllegalArgumentException> { Temporal.Formatted(" ") }
        assertThrows<IllegalArgumentException> { Temporal.Formatted("yyyy-MM-dd]") }
    }

    @Test
    fun `temporal annotation is retained on fields and getters`() {
        TemporalFixture::class.java.getDeclaredField("createdAt")
            .getAnnotation(QueryTemporal::class.java)
            .timeUnit
            .assert()
            .isEqualTo(TimeUnit.MILLISECONDS)
        TemporalFixture::class.java.getDeclaredMethod("getUpdatedAt")
            .getAnnotation(QueryTemporal::class.java)
            .timeUnit
            .assert()
            .isEqualTo(TimeUnit.SECONDS)
    }

    private data class TemporalFixture(
        @field:QueryTemporal val createdAt: Long,
        @get:QueryTemporal(TimeUnit.SECONDS) val updatedAt: Long,
    )
}
