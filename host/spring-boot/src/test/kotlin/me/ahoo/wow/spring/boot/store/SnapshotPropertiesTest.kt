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

package me.ahoo.wow.spring.boot.store

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.KernelOptions
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** `wow.snapshot.*`: a cache-miss load's timeout, below a command's default wait. */
class SnapshotPropertiesTest {
    @Test
    fun `a snapshot load should wait less than a command's default wait`() {
        SnapshotProperties().options().loadTimeout.assert().isEqualTo(KernelOptions.DEFAULT_SNAPSHOT_LOAD_TIMEOUT)
        SnapshotProperties().apply { loadTimeout = Duration.ofSeconds(4) }
            .options(Duration.ofSeconds(5)).loadTimeout.inWholeSeconds.assert().isEqualTo(4)
    }

    @Test
    fun `a timeout not below the default wait, or not above zero, should be refused`() {
        val late = assertThrows<WowConfigurationException> {
            SnapshotProperties().apply { loadTimeout = Duration.ofSeconds(10) }.options(Duration.ofSeconds(10))
        }
        late.key.assert().isEqualTo("wow.snapshot.load-timeout")
        late.message.assert().isEqualTo(
            "A snapshot load's timeout of PT10S is not between zero and a command's default wait of PT10S. Set it " +
                "above zero and below wow.wait.default-timeout (PT10S), so a command whose snapshot store does not " +
                "answer replays its log within its wait.",
        )
        val none = assertThrows<WowConfigurationException> {
            SnapshotProperties().apply { loadTimeout = Duration.ZERO }.options()
        }
        none.key.assert().isEqualTo("wow.snapshot.load-timeout")
        none.message.assert().startsWith(
            "A snapshot load's timeout of PT0S is not between zero and a command's default wait of PT10S.",
        )
    }
}
