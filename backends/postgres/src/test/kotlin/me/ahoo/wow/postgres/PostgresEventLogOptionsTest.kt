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

package me.ahoo.wow.postgres

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PostgresEventLogOptionsTest {
    @Test
    fun `the defaults are the log's, 50 ms polls backing off to 1 s and 500 a batch`() {
        val options = PostgresEventLogOptions()
        options.pollInterval.assert().isEqualTo(50.milliseconds)
        options.maxPollInterval.assert().isEqualTo(1.seconds)
        options.tailBatch.assert().isEqualTo(500)
        options.ddl.assert().isEqualTo(Ddl.AUTO)
    }

    @Test
    fun `a setting out of its range is refused where the options are built`() {
        assertThrownBy<IllegalArgumentException> { PostgresEventLogOptions(pollInterval = Duration.ZERO) }
            .hasMessage("The poll interval is positive.")
        assertThrownBy<IllegalArgumentException> {
            PostgresEventLogOptions(pollInterval = 2.seconds, maxPollInterval = 1.seconds)
        }.hasMessage("The longest poll interval is at least the poll interval.")
        assertThrownBy<IllegalArgumentException> { PostgresEventLogOptions(tailBatch = 0) }
            .hasMessage("A tail reads at least one entry at a time.")
    }

    @Test
    fun `options of the same settings are equal, and say what they hold`() {
        fun options(tailBatch: Int) = PostgresEventLogOptions(tailBatch = tailBatch, ddl = Ddl.VALIDATE)
        val options = options(7)
        options.assert().isEqualTo(options(7))
        options.hashCode().assert().isEqualTo(options(7).hashCode())
        options.assert().isNotEqualTo(options(8))
        options.assert().isEqualTo(options)
        options.equals("options").assert().isFalse()
        options.toString().assert().isEqualTo(
            "PostgresEventLogOptions(pollInterval=50ms, maxPollInterval=1s, tailBatch=7, ddl=VALIDATE)",
        )
    }
}
