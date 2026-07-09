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

package me.ahoo.wow.bi.renderer

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ClickHouseSqlSyntaxTest {
    @Test
    fun `should escape string literals`() {
        ClickHouseSqlSyntax.stringLiteral("a\\b'c").assert().isEqualTo("'a\\\\b''c'")
    }

    @Test
    fun `should escape quoted identifiers`() {
        ClickHouseSqlSyntax.quoteIdentifier("db\"x").assert().isEqualTo("\"db\\\"x\"")
        ClickHouseSqlSyntax.quoteIdentifier("db\\x").assert().isEqualTo("\"db\\\\x\"")
    }

    @Test
    fun `should reject control characters`() {
        listOf("\u0000", "line\nbreak", "tab\tvalue", "delete\u007F").forEach { value ->
            ClickHouseSqlSyntax.runCatching { quoteIdentifier(value) }.isFailure.assert().isTrue()
            ClickHouseSqlSyntax.runCatching { stringLiteral(value) }.isFailure.assert().isTrue()
        }
    }
}
