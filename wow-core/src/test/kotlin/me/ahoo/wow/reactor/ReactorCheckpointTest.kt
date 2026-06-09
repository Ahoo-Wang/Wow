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

class ReactorCheckpointTest {

    @Test
    fun `checkpointIfEnabled returns source without evaluating description when level is off`() {
        val source = Mono.just("source")
        val descriptionEvaluated = AtomicBoolean(false)

        val checkpointed = source.checkpointIfEnabled(level = CheckpointLevel.OFF) {
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

        val checkpointed = source.checkpointIfEnabled(level = CheckpointLevel.LIGHT) {
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

        val checkpointed = source.checkpointIfEnabled(level = CheckpointLevel.HEAVY) {
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
        ReactorCheckpoint
            .checkpointLevel(
                properties = emptyMap(),
                environment = mapOf(ReactorCheckpoint.CHECKPOINT_LEVEL_ENV to "heavy"),
            )
            .assert().isEqualTo(CheckpointLevel.HEAVY)
    }

    @Test
    fun `checkpoint level system property takes precedence over environment variable`() {
        ReactorCheckpoint
            .checkpointLevel(
                properties = mapOf(ReactorCheckpoint.CHECKPOINT_LEVEL_PROPERTY to "off"),
                environment = mapOf(ReactorCheckpoint.CHECKPOINT_LEVEL_ENV to "heavy"),
            )
            .assert().isEqualTo(CheckpointLevel.OFF)
    }

    @Test
    fun `hot path checkpoint property is ignored`() {
        ReactorCheckpoint
            .checkpointLevel(
                properties = mapOf("wow.reactor.hotpath-checkpoint-level" to "heavy"),
                environment = emptyMap(),
            )
            .assert().isEqualTo(CheckpointLevel.OFF)
    }

    @Test
    fun `hot path checkpoint environment variable is ignored`() {
        ReactorCheckpoint
            .checkpointLevel(
                properties = emptyMap(),
                environment = mapOf("WOW_REACTOR_HOTPATH_CHECKPOINT_LEVEL" to "heavy"),
            )
            .assert().isEqualTo(CheckpointLevel.OFF)
    }
}
