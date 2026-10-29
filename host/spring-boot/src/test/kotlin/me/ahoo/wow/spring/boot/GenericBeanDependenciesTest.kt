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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.AmbiguousDependencyException
import me.ahoo.wow.kernel.Types
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * The generated factories' dependencies from Spring's beans: a parameterized one found by every type argument, as
 * Spring matches an injection point, so two caches of one class and different views are two dependencies.
 */
class GenericBeanDependenciesTest {
    interface Cache<V> {
        val view: String
    }

    class Order
    class Payment
    class Rows

    @Configuration(proxyBeanMethods = false)
    class Caches {
        @Bean
        fun orders(): Cache<Order> = object : Cache<Order> {
            override val view: String = "order"
        }

        @Bean
        fun payments(): Cache<Payment> = object : Cache<Payment> {
            override val view: String = "payment"
        }

        @Bean
        fun rows(): Rows = Rows()
    }

    @Configuration(proxyBeanMethods = false)
    class MoreOrders {
        @Bean
        fun archived(): Cache<Order> = object : Cache<Order> {
            override val view: String = "archived"
        }
    }

    @Configuration(proxyBeanMethods = false)
    class PrimaryOrders {
        @Bean
        @Primary
        fun live(): Cache<Order> = object : Cache<Order> {
            override val view: String = "live"
        }
    }

    private fun dependenciesOf(vararg configurations: Class<*>): Pair<AnnotationConfigApplicationContext, BeanFactoryDependencies> {
        val context = AnnotationConfigApplicationContext(*configurations)
        return context to BeanFactoryDependencies(context.beanFactory)
    }

    private val orders = Types.parameterized(Cache::class.java, Order::class.java)
    private val payments = Types.parameterized(Cache::class.java, Payment::class.java)

    @Test
    fun `a parameterized dependency should be the bean of its full type`() {
        val (context, dependencies) = dependenciesOf(Caches::class.java)
        context.use {
            dependencies.get(Cache::class.java, orders, null, "PaymentSaga", "orders").view.assert().isEqualTo("order")
            dependencies.get(Cache::class.java, payments, null, "PaymentSaga", "payments").view.assert()
                .isEqualTo("payment")
            dependencies.find(Types.parameterized(Cache::class.java, Rows::class.java), null).assert().isNull()
            // A qualifier still wins, and names a bean of the full type only.
            dependencies.find(orders, "orders").assert().isSameAs(context.getBean("orders"))
            dependencies.find(orders, "payments").assert().isNull()
            // A plain class, as before.
            dependencies.get(Rows::class.java).assert().isSameAs(context.getBean("rows"))
        }
    }

    @Test
    fun `several beans of a full type should fail naming the parameter and the candidates, unless one is primary`() {
        val (context, dependencies) = dependenciesOf(Caches::class.java, MoreOrders::class.java)
        context.use {
            val failure = runCatching { dependencies.get(Cache::class.java, orders, null, "PaymentSaga", "orders") }
                .exceptionOrNull() as AmbiguousDependencyException
            failure.message.assert().contains(
                "2 dependencies are ${Cache::class.java.typeName}<${Order::class.java.typeName}> (orders, archived)",
                "the parameter orders of PaymentSaga takes one",
            )
            // The other full type is not ambiguous.
            dependencies.get(Cache::class.java, payments, null, "PaymentSaga", "payments").view.assert()
                .isEqualTo("payment")
        }
        val (primary, preferred) = dependenciesOf(Caches::class.java, PrimaryOrders::class.java)
        primary.use {
            preferred.get(Cache::class.java, orders, null, "PaymentSaga", "orders").view.assert().isEqualTo("live")
        }
    }

    @Test
    fun `several beans of a plain class should be ambiguous too`() {
        val (context, dependencies) = dependenciesOf(Caches::class.java)
        context.use {
            val failure = runCatching { dependencies.get(Cache::class.java) }.exceptionOrNull()
            (failure as AmbiguousDependencyException).candidates.assert().containsExactlyInAnyOrder(
                "orders",
                "payments"
            )
        }
    }
}
