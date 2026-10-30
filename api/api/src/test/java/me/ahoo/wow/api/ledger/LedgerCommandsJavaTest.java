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

package me.ahoo.wow.api.ledger;

import me.ahoo.wow.api.aggregate.AggregateRef;
import me.ahoo.wow.api.aggregate.AggregateType;
import me.ahoo.wow.api.result.TransformedFrom;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger's system commands and a transformed command built from Java, through their static factories (decisions
 * D216, D304): an operations tool builds the commands from plain fields.
 */
class LedgerCommandsJavaTest {
    private final AggregateRef ref = new AggregateType("sales", "order").ref("tenant", "o-1");

    @Test
    void buildsTheCommandsFromPlainFieldsOrARef() {
        RetryParked retry = RetryParked.of("OrderSaga.onPlaced", "sales", "order", "tenant", "o-1");
        assertThat(retry).isEqualTo(RetryParked.of("OrderSaga.onPlaced", ref));
        assertThat(retry.toRef()).isEqualTo(ref);
        assertThat(SkipParked.of("s", ref, 1L).getAggregate()).isEqualTo("order");
        assertThat(MarkParkedRecoverable.of("s", ref, "RECOVERABLE").getRecoverable()).isEqualTo("RECOVERABLE");
        assertThat(ApplyParkedRetrySpec.of("s", ref).getInitialBackoff()).isNull();
        assertThat(ApplyParkedRetrySpec.of("s", ref, 3).getMaxRetries()).isEqualTo(3);
    }

    @Test
    void buildsATransformedCommand() {
        assertThat(TransformedFrom.of("sales.order.Import").getAggregateId()).isNull();
        assertThat(TransformedFrom.of("sales.order.Import", "o-1").getAggregateId()).isEqualTo("o-1");
    }
}
