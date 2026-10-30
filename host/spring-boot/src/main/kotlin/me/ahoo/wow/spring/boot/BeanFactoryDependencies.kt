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

import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.kernel.AmbiguousDependencyException
import me.ahoo.wow.kernel.Dependencies
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.ResolvableType
import java.lang.reflect.Type

/**
 * The parameters of the generated model factories from Spring's [beanFactory]: a dependency
 * named by a qualifier (`@Name("…")` on the parameter) is the bean of that name, when it is of the type; one without
 * is the only bean of its type, or its primary bean when there are several. Several without a primary are ambiguous:
 * the parameter needs a qualifier. A parameterized type is matched by every type argument, as Spring matches an
 * injection point: `StateViewCache<OrderStateView>` is not `StateViewCache<PaymentStateView>`.
 *
 * A [QueryApi] or [NodeQueryApi] without a qualifier is the node's own read API (its [NodeQueryApi] bean), whatever
 * other [QueryApi] beans (a peer's client) the application has: a command function queries the read side as the
 * command's caller. The node's API is built from the node, which is built from the models, so the models take it
 * deferred ([NodeQueries]): looked up on its first use, a command's, once the node is up.
 */
internal class BeanFactoryDependencies(private val beanFactory: ListableBeanFactory) : Dependencies {
    override fun find(type: Class<*>, qualifier: String?): Any? = find(ResolvableType.forClass(type), type, qualifier)

    override fun find(type: Type, qualifier: String?): Any? = find(ResolvableType.forType(type), type, qualifier)

    private fun find(resolvable: ResolvableType, type: Type, qualifier: String?): Any? {
        if (qualifier == null && resolvable.toClass() in NODE_QUERIES && nodeQueries()) {
            return NodeQueries { beanFactory.getBean(NodeQueryApi::class.java) }
        }
        if (qualifier != null) {
            return if (beanFactory.containsBean(qualifier) && beanFactory.isTypeMatch(qualifier, resolvable)) {
                beanFactory.getBean(qualifier)
            } else {
                null
            }
        }
        val names = beanFactory.getBeanNamesForType(resolvable)
        return when (names.size) {
            0 -> null
            1 -> beanFactory.getBean(names.single())
            else -> beanFactory.getBeanProvider<Any>(resolvable).getIfUnique()
                ?: throw AmbiguousDependencyException(type, names.toList())
        }
    }

    /** Whether the node has its read API: a [NodeQueryApi] bean, looked for without creating it. */
    private fun nodeQueries(): Boolean =
        beanFactory.getBeanNamesForType(NodeQueryApi::class.java, true, false).isNotEmpty()

    private companion object {
        /** The types a model's parameter names the node's read API by. */
        val NODE_QUERIES: Set<Class<*>> = setOf(QueryApi::class.java, NodeQueryApi::class.java)
    }
}

/** The node's read API, which [node] gives on the first query: the models take it before the node is built. */
private class NodeQueries(node: () -> NodeQueryApi) : NodeQueryApi {
    private val node by lazy(node)

    override fun of(caller: Caller): Queries = node.of(caller)

    override fun trusted(): TrustedQueries = node.trusted()

    override fun toString(): String = "NodeQueries(the node's NodeQueryApi)"
}
