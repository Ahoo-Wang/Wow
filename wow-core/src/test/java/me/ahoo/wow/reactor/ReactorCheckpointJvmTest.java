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

package me.ahoo.wow.reactor;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class ReactorCheckpointJvmTest {

    @Test
    void checkpointReturnsSourceThroughJvmEntryPointWhenLevelIsOff() {
        Mono<String> source = Mono.just("source");
        AtomicBoolean descriptionEvaluated = new AtomicBoolean(false);

        Mono<String> checkpointed = ReactorCheckpointKt.checkpoint(source, CheckpointLevel.OFF, () -> {
            descriptionEvaluated.set(true);
            return "checkpoint description";
        });

        assertThat(checkpointed).isSameAs(source);
        assertThat(descriptionEvaluated.get()).isFalse();
        StepVerifier.create(checkpointed)
            .expectNext("source")
            .verifyComplete();
    }

    @Test
    void checkpointAppliesLightCheckpointThroughJvmEntryPoint() {
        Mono<String> source = Mono.just("source");
        AtomicBoolean descriptionEvaluated = new AtomicBoolean(false);

        Mono<String> checkpointed = ReactorCheckpointKt.checkpoint(source, CheckpointLevel.LIGHT, () -> {
            descriptionEvaluated.set(true);
            return "checkpoint description";
        });

        assertThat(checkpointed).isNotSameAs(source);
        assertThat(descriptionEvaluated.get()).isTrue();
        StepVerifier.create(checkpointed)
            .expectNext("source")
            .verifyComplete();
    }

    @Test
    void checkpointAppliesHeavyCheckpointThroughJvmEntryPoint() {
        Mono<String> source = Mono.just("source");
        AtomicBoolean descriptionEvaluated = new AtomicBoolean(false);

        Mono<String> checkpointed = ReactorCheckpointKt.checkpoint(source, CheckpointLevel.HEAVY, () -> {
            descriptionEvaluated.set(true);
            return "checkpoint description";
        });

        assertThat(checkpointed).isNotSameAs(source);
        assertThat(descriptionEvaluated.get()).isTrue();
        StepVerifier.create(checkpointed)
            .expectNext("source")
            .verifyComplete();
    }
}
