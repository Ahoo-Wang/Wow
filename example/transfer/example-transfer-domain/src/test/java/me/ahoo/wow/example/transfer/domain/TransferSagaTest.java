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

package me.ahoo.wow.example.transfer.domain;

import me.ahoo.wow.example.transfer.api.Entry;
import me.ahoo.wow.example.transfer.api.Prepared;
import org.junit.jupiter.api.Test;

import static me.ahoo.wow.test.SagaVerifier.*;
import static org.assertj.core.api.Assertions.*;

public class TransferSagaTest {

    @Test
    void onPrepared() {
        var event = new Prepared("to", 1L);
        sagaVerifier(TransferSaga.class)
                .when(event)
                .expectCommandBody((Entry entry) -> {
                    assertThat(entry.id()).isEqualTo(event.to());
                    assertThat(entry.amount()).isEqualTo(event.amount());
                    return null;
                })
                .verify();
    }


}

