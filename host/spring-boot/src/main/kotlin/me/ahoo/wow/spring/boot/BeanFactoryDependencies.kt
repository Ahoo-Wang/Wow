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

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.api.result.CommandResult
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
 * The node's own APIs, which a command function calls the read side and other contexts with, are built from the node,
 * which is built from the models: a [QueryApi] or [NodeQueryApi] without a qualifier is the node's read API (its only
 * [NodeQueryApi] bean, the query plane on), whatever other [QueryApi] beans (a peer's client) the application has, and
 * a [CommandClient] without one is the only [CommandClient] bean, the node's. The models take them deferred
 * ([NodeQueries], [NodeCommands]): each looked up by its name on its first use, a command's, once the node is up.
 */
internal class BeanFactoryDependencies(private val beanFactory: ListableBeanFactory) : Dependencies {
    override fun find(type: Class<*>, qualifier: String?): Any? = find(ResolvableType.forClass(type), type, qualifier)

    override fun find(type: Type, qualifier: String?): Any? = find(ResolvableType.forType(type), type, qualifier)

    private fun find(resolvable: ResolvableType, type: Type, qualifier: String?): Any? {
        if (qualifier != null) {
            return if (beanFactory.containsBean(qualifier) && beanFactory.isTypeMatch(qualifier, resolvable)) {
                beanFactory.getBean(qualifier)
            } else {
                null
            }
        }
        deferred(resolvable.toClass())?.let { return it }
        val names = beanFactory.getBeanNamesForType(resolvable)
        return when (names.size) {
            0 -> null
            1 -> beanFactory.getBean(names.single())
            else -> beanFactory.getBeanProvider<Any>(resolvable).getIfUnique()
                ?: throw AmbiguousDependencyException(type, names.toList())
        }
    }

    /**
     * The node's own API of [type], deferred; null for any other type, and when the node has none or the application
     * several, found then as any other bean.
     */
    private fun deferred(type: Class<*>): Any? = when (type) {
        QueryApi::class.java, NodeQueryApi::class.java -> onlyBeanOf(NodeQueryApi::class.java)?.let { name ->
            NodeQueries { beanFactory.getBean(name, NodeQueryApi::class.java) }
        }

        CommandClient::class.java -> onlyBeanOf(CommandClient::class.java)?.let { name ->
            NodeCommands { beanFactory.getBean(name, CommandClient::class.java) }
        }

        else -> null
    }

    /** The name of the only bean of [type], looked for without creating it; null when there is none or several. */
    private fun onlyBeanOf(type: Class<*>): String? = beanFactory.getBeanNamesForType(type, true, false).singleOrNull()
}

/** The node's read API, which [node] gives on the first query: the models take it before the node is built. */
private class NodeQueries(node: () -> NodeQueryApi) : NodeQueryApi {
    private val node by lazy(node)

    override fun of(caller: Caller): Queries = node.of(caller)

    override fun trusted(): TrustedQueries = node.trusted()

    override fun toString(): String = "NodeQueries(the node's NodeQueryApi)"
}

/** The node's command client, which [client] gives on the first command: the models take it before the node is built. */
private class NodeCommands(client: () -> CommandClient) : CommandClient {
    private val client by lazy(client)

    override fun of(caller: Caller): CommandClient = client.of(caller)

    override suspend fun send(request: CommandRequest<*>): CommandResult = client.send(request)

    override suspend fun wait(request: CommandRequest<*>): CommandResult = client.wait(request)

    override fun stream(request: CommandRequest<*>): Flow<CommandResult> = client.stream(request)

    override fun toString(): String = "NodeCommands(the node's CommandClient)"
}
