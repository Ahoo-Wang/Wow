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

package me.ahoo.wow.query.dsl

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class NestedFieldDslTest {

    private class TestNestedFieldDsl : NestedFieldDsl() {
        fun testWithNestedField(field: String): String {
            return field.withNestedField()
        }

        fun testNested(prefix: String) {
            nested(prefix)
        }

        fun currentNestedField(): String {
            return nestedField
        }
    }

    @Test
    fun `should return field unchanged when no nested field set`() {
        val dsl = TestNestedFieldDsl()
        dsl.testWithNestedField("field1").assert().isEqualTo("field1")
    }

    @Test
    fun `should prefix field with nested field`() {
        val dsl = TestNestedFieldDsl()
        dsl.testNested("state")
        dsl.testWithNestedField("field1").assert().isEqualTo("state.field1")
    }

    @Test
    fun `should return field unchanged when nested field is blank`() {
        val dsl = TestNestedFieldDsl()
        dsl.testNested("")
        dsl.testWithNestedField("field1").assert().isEqualTo("field1")
    }

    @Test
    fun `should update nested field`() {
        val dsl = TestNestedFieldDsl()
        dsl.testNested("state")
        dsl.currentNestedField().assert().isEqualTo("state")
        dsl.testNested("nested")
        dsl.testWithNestedField("field1").assert().isEqualTo("nested.field1")
    }
}
