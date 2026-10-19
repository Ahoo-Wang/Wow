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

package me.ahoo.wow.postgres.query

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.postgres.query.PostgresQueryBackend.Companion.node
import me.ahoo.wow.query.CursorPosition
import me.ahoo.wow.query.QueryExecutionException
import org.junit.jupiter.api.Test
import org.postgresql.util.PGobject
import java.math.BigDecimal
import java.time.Duration

class PostgresSnapshotQueryBackendValuesTest {
    @Test
    fun `the statement timeout rounds up to whole milliseconds and never disables itself`() {
        PostgresQueryBackend.statementTimeout(Duration.ofNanos(1)).assert().isEqualTo(1L)
        PostgresQueryBackend.statementTimeout(Duration.ZERO).assert().isEqualTo(1L)
        PostgresQueryBackend.statementTimeout(Duration.ofMillis(250)).assert().isEqualTo(250L)
        PostgresQueryBackend.statementTimeout(Duration.ofNanos(250_000_001)).assert().isEqualTo(251L)
        PostgresQueryBackend.statementTimeout(Duration.ofDays(365)).assert().isEqualTo(Int.MAX_VALUE.toLong())
    }

    @Test
    fun `column values become exact JSON`() {
        null.node().isNull.assert().isTrue()
        "a".node().stringValue().assert().isEqualTo("a")
        true.node().booleanValue().assert().isTrue()
        BigDecimal("2.50").node().decimalValue().assert().isEqualTo(BigDecimal("2.50"))
        3.node().longValue().assert().isEqualTo(3L)
        4L.node().longValue().assert().isEqualTo(4L)
        1.5.node().doubleValue().assert().isEqualTo(1.5)
        PGobject().apply {
            type = "jsonb"
            value = """{"a":[1.10]}"""
        }.node().path("a").get(0).decimalValue().assert().isEqualTo(BigDecimal("1.10"))
        PGobject().apply { type = "jsonb" }.node().isNull.assert().isTrue()
        assertThrownBy<QueryExecutionException> { Any().node() }
    }

    @Test
    fun `sort values become positions`() {
        PostgresQueryBackend.position(7).assert().isEqualTo(BigDecimal.valueOf(7))
        PostgresQueryBackend.position(BigDecimal("1.5")).assert().isEqualTo(BigDecimal("1.5"))
        PostgresQueryBackend.position("a").assert().isEqualTo("a")
        PostgresQueryBackend.position(null).assert().isNull()
        assertThrownBy<QueryExecutionException> { PostgresQueryBackend.position(Any()) }
    }

    @Test
    fun `positions round-trip exactly`() {
        val position = CursorPosition(listOf(null, "a", true, BigDecimal("12.340"), 5, 6L))
        val decoded = PostgresCursorPositionCodec.decode(PostgresCursorPositionCodec.encode(position), 6)
        decoded.values.assert().containsExactly(
            null,
            "a",
            true,
            BigDecimal("12.340"),
            BigDecimal.valueOf(5),
            BigDecimal.valueOf(6)
        )
        assertThrownBy<IllegalArgumentException> {
            PostgresCursorPositionCodec.encode(
                CursorPosition(listOf(listOf(1)))
            )
        }
        assertThrownBy<IllegalArgumentException> { PostgresCursorPositionCodec.decode("[1]".toByteArray(), 2) }
        assertThrownBy<IllegalArgumentException> { PostgresCursorPositionCodec.decode("[{}]".toByteArray(), 1) }
        assertThrownBy<IllegalArgumentException> { PostgresCursorPositionCodec.decode("{}".toByteArray(), 1) }
    }
}
