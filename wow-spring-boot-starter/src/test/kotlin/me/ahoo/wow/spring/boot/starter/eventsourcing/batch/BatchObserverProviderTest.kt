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

package me.ahoo.wow.spring.boot.starter.eventsourcing.batch

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.batch.BatchObservation
import me.ahoo.wow.infra.batch.BatchObserver
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory

class BatchObserverProviderTest {
    @Test
    fun `empty provider should preserve the core noop identity`() {
        val observer = DefaultListableBeanFactory()
            .getBeanProvider(BatchObserver::class.java)
            .toBatchObserver()

        observer.assert().isSameAs(BatchObserver.NOOP)
    }

    @Test
    fun `single provider entry should be reused`() {
        val expected = BatchObserver { }
        val beanFactory = DefaultListableBeanFactory().apply {
            registerSingleton("expected", expected)
        }

        beanFactory.getBeanProvider(BatchObserver::class.java)
            .toBatchObserver()
            .assert().isSameAs(expected)
    }

    @Test
    fun `multiple provider entries should be composed in order`() {
        val calls = mutableListOf<String>()
        val beanFactory = DefaultListableBeanFactory().apply {
            registerSingleton("first", BatchObserver { calls += "first" })
            registerSingleton("second", BatchObserver { calls += "second" })
        }

        beanFactory.getBeanProvider(BatchObserver::class.java)
            .toBatchObserver()
            .onObservation(
                BatchObservation.RequestCancelled(
                    coordinatorName = "test",
                    coordinatorInstanceId = 1,
                )
            )

        calls.assert().containsExactly("first", "second")
    }
}
