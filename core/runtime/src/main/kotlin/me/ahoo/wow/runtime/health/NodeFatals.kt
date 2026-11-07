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

package me.ahoo.wow.runtime.health

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import java.time.Clock
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The fatal errors of one [node]'s own work: a [VirtualMachineError] (out of memory, a stack overflow, an internal
 * error of the virtual machine) that one of its loops met. After one, the JVM's state is not to be trusted, and a
 * retry could loop on the same error, so nothing retries that work in the process: the first fatal error makes the node
 * fatal for good ([first]). [NodeHealth] then reports its liveness and readiness `DOWN` until the process ends, so its
 * orchestrator restarts it, and the node hands off what it holds ([listen]), so the other nodes take the work over from
 * its checkpoints even where nothing restarts it.
 *
 * Each error is recorded once ([record]), by whoever meets it first: the loop that met it, which knows what it was
 * doing, or the handler of the uncaught failures ([handler]) of the node's coroutine scopes, each of which carries the
 * part of the node it runs ([contextOf]). The first is logged at ERROR with its trace, a later one at WARN without it;
 * each is observed ([ObservedEvent.Fatal]), which the observability module counts as `wow.node.fatal.errors`.
 *
 * [NONE], the default of a part built outside a node, records nothing: it logs each error at ERROR, and gives its scopes
 * no handler of their own.
 */
@InternalWowApi
class NodeFatals private constructor(
    val node: String,
    private val observer: Observer,
    private val clock: Clock,
    /** Whether it keeps what it records: false for [NONE] alone. */
    private val keeps: Boolean,
) {
    constructor(node: String, observer: Observer = Observer.NONE, clock: Clock = Clock.systemUTC()) :
        this(node, observer, clock, keeps = true)

    /** The errors recorded, each once: a [Throwable] is compared by identity. */
    private val seen: MutableMap<Throwable, Boolean> = Collections.synchronizedMap(WeakHashMap())
    private val recorded = AtomicInteger()
    private val listeners = CopyOnWriteArrayList<(NodeFatal) -> Unit>()

    /** The first fatal error the node met; null while it has met none. */
    @Volatile
    var first: NodeFatal? = null
        private set

    /** Whether the node met a fatal error. */
    val isFatal: Boolean get() = first != null

    /** How many fatal errors the node met. */
    val count: Int get() = recorded.get()

    /** Calls [listener], once, with the node's first fatal error, at once when it met one already. */
    fun listen(listener: (NodeFatal) -> Unit) {
        listeners += listener
        first?.let { fatal -> if (listeners.remove(listener)) tell(listener, fatal) }
    }

    /**
     * Records [error], which [component]'s work on [name] (in [partition], when it runs one) met: the node is fatal from
     * now on. An error recorded already is not recorded again.
     */
    fun record(
        component: ObservedEvent.Fatal.Component,
        name: String,
        error: VirtualMachineError,
        partition: Int? = null,
    ) {
        if (!keeps) {
            logger.error(error) { "${where(component, name, partition)} met a fatal error; its work stops." }
            return
        }
        if (seen.put(error, true) != null) {
            return
        }
        val fatal = NodeFatal(component, name, partition, error.javaClass.name, clock.millis())
        val isFirst = synchronized(this) {
            (first == null).also { if (it) first = fatal }
        }
        recorded.incrementAndGet()
        quietly {
            if (isFirst) {
                logger.error(error) {
                    "Node $node met a fatal error in ${where(component, name, partition)}: its liveness is DOWN until " +
                        "the process ends, and it hands off what it holds. Run the JVM with " +
                        "-XX:+ExitOnOutOfMemoryError, so an OutOfMemoryError ends the process at once."
                }
            } else {
                logger.warn { "Node $node met another fatal error in ${where(component, name, partition)}: $error" }
            }
        }
        quietly { observer.observed(ObservedEvent.Fatal(component, name, fatal.error)) }
        if (isFirst) {
            listeners.toList().forEach { listener -> if (listeners.remove(listener)) tell(listener, fatal) }
        }
    }

    /**
     * The handler of the failures that escape a coroutine of the node's scopes: a [VirtualMachineError] is
     * [record]ed as the scope's part of the node ([contextOf]) met it; any other failure ended a coroutine nothing
     * restarts, and is logged at ERROR.
     */
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { context, failure ->
        val loop = context[NodeLoop]
        if (failure is VirtualMachineError) {
            record(loop?.component ?: ObservedEvent.Fatal.Component.NODE, loop?.name ?: node, failure)
        } else {
            logger.error(failure) {
                "A coroutine of ${loop?.let { where(it.component, it.name, null) } ?: "node $node"} failed; nothing " +
                    "restarts it."
            }
        }
    }

    /**
     * What a coroutine scope of [component]'s work on [name] adds to its context: the [handler], told which part of the
     * node the scope runs. Nothing for [NONE].
     */
    fun contextOf(component: ObservedEvent.Fatal.Component, name: String): CoroutineContext =
        if (keeps) handler + NodeLoop(component, name) else EmptyCoroutineContext

    private fun tell(listener: (NodeFatal) -> Unit, fatal: NodeFatal) = quietly { listener(fatal) }

    /** Runs [block], whatever it fails with: the error is recorded already, and its record goes no further. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Throwable) {
            // Out of memory again, say: the node is fatal already.
        }
    }

    private fun where(component: ObservedEvent.Fatal.Component, name: String, partition: Int?): String =
        "${component.value} $name" + (partition?.let { " partition $it" } ?: "")

    /** The part of the node a coroutine scope runs, which [handler] records a fatal error as. */
    private class NodeLoop(
        val component: ObservedEvent.Fatal.Component,
        val name: String,
    ) : AbstractCoroutineContextElement(NodeLoop) {
        companion object Key : CoroutineContext.Key<NodeLoop>
    }

    @InternalWowApi
    companion object {
        /** Records nothing: each error is logged, and the scopes get no handler. */
        val NONE: NodeFatals = NodeFatals("", Observer.NONE, Clock.systemUTC(), keeps = false)
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * A fatal error a node met ([NodeFatals]): in its [component]'s work on [name], in [partition] when that work runs one,
 * an [error] of that class (its full name), at [at] (epoch milliseconds).
 */
@InternalWowApi
data class NodeFatal(
    val component: ObservedEvent.Fatal.Component,
    val name: String,
    val partition: Int?,
    val error: String,
    val at: Long,
)
