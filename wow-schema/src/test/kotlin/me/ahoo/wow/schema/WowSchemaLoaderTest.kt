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

package me.ahoo.wow.schema

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.AggregateId
import org.junit.jupiter.api.Test

class WowSchemaLoaderTest {

    @Test
    fun `should load schema resource as string`() {
        val schema = WowSchemaLoader.loadAsString("AggregateId")

        schema.assert().contains("\"aggregateId\"")
    }

    @Test
    fun `should load schema resource as object node`() {
        val schema = WowSchemaLoader.load("AggregateId")

        schema.get("properties").assert().isNotNull()
    }

    @Test
    fun `should load schema resource by type simple name`() {
        val schema = WowSchemaLoader.load(AggregateId::class.java)

        schema.get("properties").assert().isNotNull()
    }

    @Test
    fun `should throw when loading non-existent schema resource`() {
        assertThrownBy<IllegalArgumentException> {
            WowSchemaLoader.loadAsString("not_found")
        }
    }
}
