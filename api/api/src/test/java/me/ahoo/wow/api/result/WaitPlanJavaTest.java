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

package me.ahoo.wow.api.result;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A wait plan built from Java: static factories, overloads and {@code java.time.Duration} timeouts. */
class WaitPlanJavaTest {

    @Test
    void buildsAChainWithAJavaTimeout() {
        WaitPlan plan = WaitPlan.of(Hop.committed(), Hop.handled("order.saga"), Hop.committed("stock"))
            .within(Duration.ofSeconds(3));
        assertThat(plan.getHops()).containsExactly(
            Hop.committed(),
            Hop.handled("order.saga", null),
            Hop.committed("stock")
        );
        assertThat(plan.getJavaTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(plan.isDefault()).isFalse();
    }

    @Test
    void givesAPlanAJavaTimeout() {
        WaitPlan plan = WaitPlan.of(Hop.snapshot()).within(Duration.ofMillis(1500));
        assertThat(plan.getJavaTimeout()).isEqualTo(Duration.ofMillis(1500));
        assertThat(plan.getHops()).containsExactly(Hop.snapshot(null));
        assertThatThrownBy(() -> WaitPlan.COMMITTED.within(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tellsTheDefaultFromAnExplicitSnapshot() {
        assertThat(WaitPlan.DEFAULT.isDefault()).isTrue();
        assertThat(WaitPlan.DEFAULT.within(Duration.ofSeconds(30)).isDefault()).isTrue();
        assertThat(WaitPlan.SNAPSHOT.isDefault()).isFalse();
        assertThat(WaitPlan.of(Hop.snapshot())).isEqualTo(WaitPlan.SNAPSHOT);
        assertThat(WaitPlan.DEFAULT).isNotEqualTo(WaitPlan.SNAPSHOT);
        assertThat(WaitPlan.DEFAULT.getJavaTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
