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

package me.ahoo.wow.spring.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.toStringWithAlias
import org.junit.jupiter.api.Test

class QueryGatewayBeanNamesTest {
    private val namedAggregate = MaterializedNamedAggregate("query-bean-name-test", "order")

    @Test
    fun `snapshot gateway bean name should use the aliased aggregate name`() {
        namedAggregate.snapshotQueryGatewayBeanName()
            .assert().isEqualTo("${namedAggregate.toStringWithAlias()}.SnapshotQueryGateway")
    }

    @Test
    fun `event stream gateway bean name should use the aliased aggregate name`() {
        namedAggregate.eventStreamQueryGatewayBeanName()
            .assert().isEqualTo("${namedAggregate.toStringWithAlias()}.EventStreamQueryGateway")
    }
}
