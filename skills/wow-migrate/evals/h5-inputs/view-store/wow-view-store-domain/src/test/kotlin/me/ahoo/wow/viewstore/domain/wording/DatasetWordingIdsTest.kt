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

package me.ahoo.wow.viewstore.domain.wording

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class DatasetWordingIdsTest {
    @Test
    fun `the same tenant, application and dataset give the same id`() {
        DatasetWordingIds.of("t", "app", "a.orders").assert()
            .isEqualTo(DatasetWordingIds.of("t", "app", "a.orders"))
            .hasSize(32)
    }

    @Test
    fun `any other tenant, application or dataset gives another id`() {
        val base = DatasetWordingIds.of("t", "app", "a.orders")
        DatasetWordingIds.of("u", "app", "a.orders").assert().isNotEqualTo(base)
        DatasetWordingIds.of("t", "other", "a.orders").assert().isNotEqualTo(base)
        DatasetWordingIds.of("t", "app", "a.refunds").assert().isNotEqualTo(base)
        // The separator keeps a part's end from passing as the next part's start.
        DatasetWordingIds.of("t", "appa", ".orders").assert().isNotEqualTo(base)
    }
}
