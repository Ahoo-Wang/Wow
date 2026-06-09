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
    fun `checkpointIfEnabled returns source without evaluating description when level is off`() {
        val source = Mono.just("source")
        val descriptionEvaluated = AtomicBoolean(false)

        val checkpointed = source.checkpointIfEnabled(level = HotPathCheckpointLevel.OFF) {
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
    fun `checkpointIfEnabled applies light checkpoint when level is light`() {
        val source = Mono.just("source")
        val descriptionEvaluated = AtomicBoolean(false)

        val checkpointed = source.checkpointIfEnabled(level = HotPathCheckpointLevel.LIGHT) {
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
    fun `checkpointIfEnabled applies heavy checkpoint when level is heavy`() {
        val source = Mono.just("source")
        val descriptionEvaluated = AtomicBoolean(false)

        val checkpointed = source.checkpointIfEnabled(level = HotPathCheckpointLevel.HEAVY) {
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
    fun `checkpoint level config can be set by environment variable`() {
        HotPathCheckpoint
            .checkpointLevel(
                properties = emptyMap(),
                environment = mapOf(HotPathCheckpoint.CHECKPOINT_LEVEL_ENV to "heavy"),
            )
            .assert().isEqualTo(HotPathCheckpointLevel.HEAVY)
    }

    @Test
    fun `checkpoint level system property takes precedence over environment variable`() {
        HotPathCheckpoint
            .checkpointLevel(
                properties = mapOf(HotPathCheckpoint.CHECKPOINT_LEVEL_PROPERTY to "off"),
                environment = mapOf(HotPathCheckpoint.CHECKPOINT_LEVEL_ENV to "heavy"),
            )
            .assert().isEqualTo(HotPathCheckpointLevel.OFF)
    }

    @Test
    fun `legacy detailed checkpoint property is ignored`() {
        HotPathCheckpoint
            .checkpointLevel(
                properties = mapOf("wow.reactor.detailed-hotpath-checkpoints" to "true"),
                environment = emptyMap(),
            )
            .assert().isEqualTo(HotPathCheckpointLevel.OFF)
    }

    @Test
    fun `legacy detailed checkpoint environment variable is ignored`() {
        HotPathCheckpoint
            .checkpointLevel(
                properties = emptyMap(),
                environment = mapOf("WOW_REACTOR_DETAILED_HOTPATH_CHECKPOINTS" to "true"),
            )
            .assert().isEqualTo(HotPathCheckpointLevel.OFF)
    }
}
