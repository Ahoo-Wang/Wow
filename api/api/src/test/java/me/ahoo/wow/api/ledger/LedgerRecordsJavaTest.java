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

import me.ahoo.wow.api.result.TransformedFrom;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger records and a transformed command built from Java, through their static factories (decision D216). */
class LedgerRecordsJavaTest {

    @Test
    void buildsARetrySpecWithAndWithoutApplied() {
        LedgerRetrySpec spec = LedgerRetrySpec.of(3, "PT1M", "PT2M");
        assertThat(spec.getApplied()).isFalse();
        assertThat(LedgerRetrySpec.of(3, "PT1M", "PT2M", true).getApplied()).isTrue();
        assertThat(spec).isEqualTo(LedgerRetrySpec.of(3, "PT1M", "PT2M", false));
    }

    @Test
    void buildsAResolution() {
        LedgerAggregate aggregate = new LedgerAggregate("sales", "order", "tenant", "o-1");
        LedgerResolution resolution =
            LedgerResolution.of("svc", "sales", "s", aggregate, null, LedgerResolution.NOT_PARKED, 7L);
        assertThat(resolution.getFailedAt()).isNull();
        assertThat(resolution.getOutcome()).isEqualTo("NOT_PARKED");
    }

    @Test
    void buildsATransformedCommand() {
        assertThat(TransformedFrom.of("sales.order.Import").getAggregateId()).isNull();
        assertThat(TransformedFrom.of("sales.order.Import", "o-1").getAggregateId()).isEqualTo("o-1");
    }
}
