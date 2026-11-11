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

package me.ahoo.wow.mongo.log

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.mongo.MongoSharding
import me.ahoo.wow.mongo.MongoTimeouts
import me.ahoo.wow.spi.Ddl
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MongoEventLogOptionsTest {
    @Test
    fun `the defaults are the log's, 50 ms polls backing off to 1 s, 500 a batch and 5 s an append`() {
        val options = MongoEventLogOptions()
        options.pollInterval.assert().isEqualTo(50.milliseconds)
        options.maxPollInterval.assert().isEqualTo(1.seconds)
        options.tailBatch.assert().isEqualTo(500)
        options.appendTimeout.assert().isEqualTo(5.seconds)
        options.operationTimeout.assert().isEqualTo(MongoTimeouts.OPERATION)
        options.ddl.assert().isEqualTo(Ddl.AUTO)
        options.sharding.assert().isEqualTo(MongoSharding.AUTO)
    }

    @Test
    fun `a setting out of its range is refused where the options are built`() {
        assertThrownBy<IllegalArgumentException> { MongoEventLogOptions(pollInterval = Duration.ZERO) }
            .hasMessage("The poll interval is positive.")
        assertThrownBy<IllegalArgumentException> {
            MongoEventLogOptions(pollInterval = 2.seconds, maxPollInterval = 1.seconds)
        }.hasMessage("The longest poll interval is at least the poll interval.")
        assertThrownBy<IllegalArgumentException> { MongoEventLogOptions(tailBatch = 0) }
            .hasMessage("A tail reads at least one sequence at a time.")
        assertThrownBy<IllegalArgumentException> { MongoEventLogOptions(appendTimeout = Duration.ZERO) }
            .hasMessage("The append timeout is positive.")
        assertThrownBy<IllegalArgumentException> { MongoEventLogOptions(operationTimeout = (-1).seconds) }
            .hasMessage("The operation timeout is positive.")
    }

    @Test
    fun `options of the same settings are equal, and say what they hold`() {
        fun options(tailBatch: Int) =
            MongoEventLogOptions(tailBatch = tailBatch, ddl = Ddl.VALIDATE, sharding = MongoSharding.NONE)
        val options = options(7)
        options.assert().isEqualTo(options(7))
        options.hashCode().assert().isEqualTo(options(7).hashCode())
        options.assert().isNotEqualTo(options(8))
        options.assert().isEqualTo(options)
        options.equals("options").assert().isFalse()
        options.toString().assert().isEqualTo(
            "MongoEventLogOptions(pollInterval=50ms, maxPollInterval=1s, tailBatch=7, appendTimeout=5s, " +
                "operationTimeout=${MongoTimeouts.OPERATION}, ddl=VALIDATE, sharding=NONE)",
        )
    }
}
