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
import me.ahoo.wow.api.query.QueryValueKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class QuerySchemaTypesTest {
    @Test
    fun `schema identifiers must be safe single segments`() {
        QueryModel("snapshot").assert().isEqualTo(QueryModel.SNAPSHOT)
        QueryModel.from("dataset").assert().isEqualTo(QueryModel.DATASET)
        QueryModel.SNAPSHOT.toString().assert().isEqualTo("snapshot")
        QueryValueType("string").assert().isEqualTo(QueryValueType.STRING)
        QueryValueType.from("decimal").assert().isEqualTo(QueryValueType.DECIMAL)
        QueryValueType.BOOLEAN.toString().assert().isEqualTo("boolean")
        QueryModel("event-store").value.assert().isEqualTo("event-store")

        assertThrows<IllegalArgumentException> { QueryModel("../snapshot") }
        assertThrows<IllegalArgumentException> { QueryModel("") }
        assertThrows<IllegalArgumentException> { QueryValueType("/string") }
        assertThrows<IllegalArgumentException> { QueryValueType("1st") }
        assertThrows<IllegalArgumentException> { QueryModel("SNAPSHOT") }
        assertThrows<IllegalArgumentException> { QueryValueType("big_int") }
    }

    @Test
    fun `a custom model or value type in another spelling is refused with the spelling it takes`() {
        assertThrows<IllegalArgumentException> { QueryModel("CUSTOM_MODEL") }.message.assert()
            .isEqualTo("A query model is lower-kebab like the built-ins: [CUSTOM_MODEL] is not; write [custom-model].")
        assertThrows<IllegalArgumentException> { QueryValueType("BigInt") }.message.assert()
            .isEqualTo("A query value type is lower-kebab like the built-ins: [BigInt] is not; write [big-int].")
    }

    @Test
    fun `temporal variants keep their facts`() {
        Temporal.Epoch().timeUnit.assert().isEqualTo(TimeUnit.MILLISECONDS)
        Temporal.Epoch(TimeUnit.SECONDS).assert().isEqualTo(Temporal.Epoch(TimeUnit.SECONDS))
        Temporal.Formatted("yyyy-MM-dd").pattern.assert().isEqualTo("yyyy-MM-dd")
        Temporal.Date.assert().isInstanceOf(QuerySemanticType::class.java)
    }

    @Test
    fun `formatted temporal requires a usable pattern`() {
        assertThrows<IllegalArgumentException> { Temporal.Formatted(" ") }
        assertThrows<IllegalArgumentException> { Temporal.Formatted("yyyy-MM-dd]") }
    }

    @Test
    fun `a deprecation may omit its message`() {
        QueryDeprecation().message.assert().isNull()
        QueryDeprecation("use b").message.assert().isEqualTo("use b")
    }

    @Test
    fun `the closed vocabularies keep their order`() {
        QueryCardinality.entries.assert().containsExactly(QueryCardinality.SINGLE, QueryCardinality.MANY)
        QueryValueKind.entries.assert().containsExactly(
            QueryValueKind.UNKNOWN,
            QueryValueKind.NULL,
            QueryValueKind.SCALAR,
            QueryValueKind.OBJECT,
            QueryValueKind.ARRAY,
            QueryValueKind.UNION,
        )
        QueryCapability.entries.size.assert().isEqualTo(12)
    }
}
