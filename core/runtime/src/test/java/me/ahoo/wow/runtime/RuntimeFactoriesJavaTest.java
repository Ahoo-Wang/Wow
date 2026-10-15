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

package me.ahoo.wow.runtime;

import me.ahoo.wow.api.aggregate.Scope;
import me.ahoo.wow.api.decide.Causation;
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore;
import me.ahoo.wow.kernel.memory.InMemoryEventLog;
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger;
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore;
import me.ahoo.wow.runtime.admission.CallContext;
import me.ahoo.wow.runtime.node.ContextBackends;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A call context and a context's backends built from Java, through their static factories (decision D216). */
class RuntimeFactoriesJavaTest {

    @Test
    void buildsACallContext() {
        Causation causation = new Causation("root-1", "OrderSaga.onPlaced");
        CallContext call = CallContext.of("call-1", "t-1", new Scope("alice", null), causation);
        assertThat(call.getOperator()).isNull();
        assertThat(call.getWait()).isNull();
        assertThat(call.getCausation()).isEqualTo(causation);
    }

    @Test
    void buildsTheBackendsOfAContext() {
        ContextBackends backends = ContextBackends.of(
            new InMemoryEventLog(2),
            new InMemoryCheckpointStore(),
            new InMemoryLeaseStore(),
            new InMemoryFailureLedger()
        );
        assertThat(backends.getStore()).isEqualTo(ContextBackends.DEFAULT_STORE);
        assertThat(backends.getSnapshots()).isNull();
        assertThat(backends.getGroups()).isEmpty();
    }
}
