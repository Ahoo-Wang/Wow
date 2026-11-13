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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** An enumeration whose constant has a body of its own, so the constant's class is a subclass of the enumeration. */
    private enum class Spelled {
        PLAIN,
        WITH_BODY {
            override fun word(): String = "body"
        },
        ;

        open fun word(): String = "plain"
    }

    @Test
    fun `a constant with a body of its own should be spelled as any other, by its enumeration's names`() {
        Spelled.WITH_BODY.word().assert().isEqualTo("body")
        WireNames.of(Spelled.WITH_BODY).assert().isEqualTo("with-body")
        WireNames.of(Spelled.PLAIN).assert().isEqualTo("plain")
    }

    @Test
    fun `a constant's wire name should be spelled once, the same instance on every call`() {
        val first = WireNames.of(Misfire.CATCH_UP)
        WireNames.of(Misfire.CATCH_UP).assert().isSameAs(first)
        WireNames.of(Misfire.CATCH_UP).assert().isEqualTo(WireNames.of(Misfire.CATCH_UP.name))
    }

    @Test
    fun `threads writing a constant at once should all read the one instance`() {
        val threads = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            // A constant of an enumeration no other test has written, so its names are spelled while the threads race.
            val read = (1..threads).map {
                pool.submit<String> {
                    start.await()
                    WireNames.of(Raced.SECOND_VALUE)
                }
            }
            start.countDown()
            val names = read.map { it.get(10, TimeUnit.SECONDS) }
            names.assert().allMatch { it == "second-value" }
            names.distinctBy { System.identityHashCode(it) }.assert().hasSize(1)
            WireNames.of(Raced.SECOND_VALUE).assert().isSameAs(names.first())
        } finally {
            pool.shutdownNow()
        }
    }

    private enum class Raced { FIRST, SECOND_VALUE }
}
