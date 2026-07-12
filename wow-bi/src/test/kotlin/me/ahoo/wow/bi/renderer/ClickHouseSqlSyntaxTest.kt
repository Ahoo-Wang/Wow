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
    fun `should UTF-8 hex escape every control in literals and identifiers`() {
        listOf(
            '\u0000' to "00",
            '\b' to "08",
            '\u000C' to "0C",
            '\n' to "0A",
            '\r' to "0D",
            '\t' to "09",
            '\u000B' to "0B",
            '\u001F' to "1F",
            '\u007F' to "7F",
            '\u0080' to "C2\\x80",
            '\u009F' to "C2\\x9F",
        ).forEach { (control, encoded) ->
            ClickHouseSqlSyntax.stringLiteral("A${control}B").assert().isEqualTo("'A\\x${encoded}B'")
            ClickHouseSqlSyntax.quoteIdentifier("A${control}B").assert().isEqualTo("\"A\\x${encoded}B\"")
        }
    }
}
