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

package me.ahoo.wow.clickhouse.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The dataset settings leave alone how rows are written, which Wow reads, and never let a limit answer part of a result
 * as if it were all of it (#6295); every other server setting stays the host's.
 */
class ClickHouseDatasetSettingsTest {
    private fun refusal(name: String, value: String): String? =
        assertThrows<IllegalArgumentException> { ClickHouseDatasetOptions(settings = mapOf(name to value)) }.message

    @Test
    fun `a setting that decides how rows are written is refused`() {
        refusal("output_format_json_quote_decimals", "1").assert()
            .contains("[output_format_json_quote_decimals]").contains("leave it out")
        refusal("output_format_json_named_tuples_as_objects", "0").assert().contains("leave it out")
        refusal("date_time_output_format", "simple").assert().contains("[date_time_output_format]")
        refusal("http_write_exception_in_output_format", "1").assert().contains("leave it out")
        refusal("enable_extended_results_for_datetime_functions", "0").assert().contains("leave it out")
    }

    @Test
    fun `an overflow mode that answers part of a result is refused`() {
        refusal("result_overflow_mode", "break").assert()
            .contains("[result_overflow_mode=break]").contains("set it to [throw] or leave it out")
        refusal("read_overflow_mode", "break").assert().contains("[read_overflow_mode=break]")
        refusal("read_overflow_mode_leaf", "break").assert().contains("[read_overflow_mode_leaf=break]")
        refusal("group_by_overflow_mode", "any").assert().contains("[group_by_overflow_mode=any]")
    }

    @Test
    fun `limits that fail a query, and the overflow modes that fail it, stay the host's`() {
        val settings = mapOf(
            "max_memory_usage" to "1000000",
            "max_result_rows" to "1",
            "result_overflow_mode" to "throw",
            "read_overflow_mode" to "THROW",
            "max_threads" to "2",
        )
        ClickHouseDatasetOptions(settings = settings).settings.assert().isEqualTo(settings)
    }
}
