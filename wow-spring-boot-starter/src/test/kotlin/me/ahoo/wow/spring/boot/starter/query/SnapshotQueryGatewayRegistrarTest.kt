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

package me.ahoo.wow.spring.boot.starter.query

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.gateway.SnapshotQueryGateway
import me.ahoo.wow.query.gateway.SnapshotQueryGatewayFactory
import me.ahoo.wow.spring.query.SnapshotQueryGatewayRegistrar
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCommandAggregate
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.util.AbstractMap

class SnapshotQueryGatewayRegistrarTest {
    @Test
    fun `should register gateway with actual state generic`() {
        val beanFactory = DefaultListableBeanFactory()
        val gateway = mockk<SnapshotQueryGateway<MockStateAggregate>>()
        val factory = mockk<SnapshotQueryGatewayFactory>()
        every { factory.create<MockStateAggregate>(any()) } returns gateway
        beanFactory.registerSingleton("snapshotQueryGatewayFactory", factory)
        val registrar = SnapshotQueryGatewayRegistrar()
        registrar.setBeanFactory(beanFactory)
        val namedAggregate = MOCK_AGGREGATE_METADATA.namedAggregate.materialize() as MaterializedNamedAggregate

        registrar.registerQueryService(
            AbstractMap.SimpleEntry(namedAggregate, MockCommandAggregate::class.java),
            beanFactory
        )

        val beanName = "${namedAggregate.toStringWithAlias()}.SnapshotQueryGateway"
        val definition = beanFactory.getBeanDefinition(beanName)
        definition.resolvableType.getGeneric(0).resolve().assert().isEqualTo(MockStateAggregate::class.java)
        beanFactory.getBean(beanName).assert().isSameAs(gateway)
    }
}
