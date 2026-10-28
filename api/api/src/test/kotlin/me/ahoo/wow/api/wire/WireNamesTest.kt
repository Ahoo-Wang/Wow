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

package me.ahoo.wow.api.wire

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.schedule.Misfire
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WireNamesTest {
    @ParameterizedTest
    @CsvSource(
        "SUCCEEDED, succeeded",
        "CATCH_UP, catch-up",
        "COMMAND_RECEIPT, command-receipt",
        "NotBlank, not-blank",
        "PositiveOrZero, positive-or-zero",
        "Min, min",
        "URLPattern, url-pattern",
        "Base64Value, base64-value",
        "ISO8601, iso8601",
    )
    fun `a name should be lower-kebab, a word starting at a case change or an underscore`(name: String, wire: String) {
        WireNames.of(name).assert().isEqualTo(wire)
    }

    @Test
    fun `an enumeration's value should be its constant's name so spelled`() {
        WireNames.of(Misfire.CATCH_UP).assert().isEqualTo("catch-up")
        WireNames.of(CommandResult.Status.REJECTED).assert().isEqualTo("rejected")
    }
}
