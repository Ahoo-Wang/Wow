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

package me.ahoo.wow.reactor

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicBoolean

class HotPathCheckpointTest {

    @Test
    fun `checkpointIfEnabled returns source without evaluating description when disabled`() {
        val source = Mono.just("source")
        val descriptionEvaluated = AtomicBoolean(false)

        val checkpointed = source.checkpointIfEnabled(detailedEnabled = false) {
            descriptionEvaluated.set(true)
            "checkpoint description"
        }

        checkpointed.assert().isSameAs(source)
        descriptionEvaluated.get().assert().isFalse()
        StepVerifier.create(checkpointed)
            .expectNext("source")
            .verifyComplete()
    }

    @Test
    fun `checkpointIfEnabled applies checkpoint when enabled`() {
        val source = Mono.just("source")
        val descriptionEvaluated = AtomicBoolean(false)

        val checkpointed = source.checkpointIfEnabled(detailedEnabled = true) {
            descriptionEvaluated.set(true)
            "checkpoint description"
        }

        checkpointed.assert().isNotSameAs(source)
        descriptionEvaluated.get().assert().isTrue()
        StepVerifier.create(checkpointed)
            .expectNext("source")
            .verifyComplete()
    }

    @Test
    fun `detailed checkpoint config can be enabled by environment variable`() {
        HotPathCheckpoint
            .detailedCheckpointEnabled(
                properties = emptyMap(),
                environment = mapOf(HotPathCheckpoint.DETAILED_CHECKPOINT_ENV to "true"),
            )
            .assert().isTrue()
    }

    @Test
    fun `detailed checkpoint system property takes precedence over environment variable`() {
        HotPathCheckpoint
            .detailedCheckpointEnabled(
                properties = mapOf(HotPathCheckpoint.DETAILED_CHECKPOINT_PROPERTY to "false"),
                environment = mapOf(HotPathCheckpoint.DETAILED_CHECKPOINT_ENV to "true"),
            )
            .assert().isFalse()
    }
}
