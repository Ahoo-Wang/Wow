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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.TrustedQueries
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A model's read API (review PA-6): a `QueryApi` or `NodeQueryApi` parameter without a qualifier is the node's own,
 * deferred, since the node's API is built from the models; another one is taken by its name. Without the node's
 * API, the parameter has no dependency, and the start fails as for any other.
 */
class NodeQueryDependenciesTest {
    private val queries: Queries = mockk()
    private val trusted: TrustedQueries = mockk()

    /** The node's read API, and a peer's client of the same interface. */
    @Configuration(proxyBeanMethods = false)
    class ReadApis {
        @Bean
        fun node(): NodeQueryApi = mockk {
            every { of(any()) } returns mockk()
            every { trusted() } returns mockk()
        }

        @Bean
        fun peer(): QueryApi = mockk()
    }

    /** A peer's client alone: a node with its query plane off has no read API of its own. */
    @Configuration(proxyBeanMethods = false)
    class PeerOnly {
        @Bean
        fun peer(): QueryApi = mockk()
    }

    private fun dependenciesOf(vararg configurations: Class<*>): Pair<AnnotationConfigApplicationContext, BeanFactoryDependencies> {
        val context = AnnotationConfigApplicationContext(*configurations)
        return context to BeanFactoryDependencies(context.beanFactory)
    }

    @Test
    fun `a read API parameter should be the node's own, looked up on its first query`() {
        val (context, dependencies) = dependenciesOf(ReadApis::class.java)
        context.use {
            val node = context.getBean("node", NodeQueryApi::class.java)
            every { node.of(Caller.ANONYMOUS) } returns queries
            every { node.trusted() } returns trusted
            // Not the peer's, though it is a QueryApi too: the node's, behind the deferred one.
            val asQueryApi = dependencies.get(QueryApi::class.java) as NodeQueryApi
            asQueryApi.assert().isNotSameAs(node)
            asQueryApi.of(Caller.ANONYMOUS).assert().isSameAs(queries)
            asQueryApi.trusted().assert().isSameAs(trusted)
            asQueryApi.toString().assert().contains("NodeQueryApi")
            dependencies.get(NodeQueryApi::class.java).trusted().assert().isSameAs(trusted)
            // A qualifier names the bean itself.
            dependencies.find(QueryApi::class.java, "peer").assert().isSameAs(context.getBean("peer"))
        }
    }

    @Test
    fun `without the node's read API a read API parameter should be found as any other bean`() {
        val (context, dependencies) = dependenciesOf(PeerOnly::class.java)
        context.use {
            // The peer's client is no stand-in for the node's: an unqualified QueryApi is then the only bean of it.
            dependencies.find(QueryApi::class.java, null).assert().isSameAs(context.getBean("peer"))
            dependencies.find(NodeQueryApi::class.java, null).assert().isNull()
        }
    }
}
