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

package me.ahoo.wow.command.factory

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.functions

/**
 * Interface for rewriting command builders before command execution.
 *
 * CommandBuilderRewriters allow modification of command builders to add
 * additional context, validation, or transformation logic before commands
 * are processed by the command bus.
 *
 * @see CommandBuilder
 * @see CommandBuilderRewriterRegistry
 */
interface CommandBuilderRewriter {
    /**
     * The command type that this rewriter supports.
     */
    val supportedCommandType: Class<*>

    /**
     * Rewrites the command builder, potentially modifying its properties.
     *
     * @param commandBuilder the command builder to rewrite
     * @return a Mono emitting the rewritten command builder
     */
    fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder>
}

/**
 * Decorator that executes command builder rewriting on a blocking scheduler.
 *
 * This wrapper ensures that potentially blocking rewrite operations are
 * executed on a separate thread pool to avoid blocking the reactive pipeline.
 *
 * @param delegate the original rewriter to wrap
 * @param scheduler the scheduler for executing blocking operations (default: boundedElastic)
 * @see CommandBuilderRewriter
 * @see Decorator
 */
class BlockingCommandBuilderRewriter(
    override val delegate: CommandBuilderRewriter,
    private val scheduler: Scheduler = Schedulers.boundedElastic()
) : Decorator<CommandBuilderRewriter>,
    CommandBuilderRewriter by delegate {
    override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> =
        Mono
            .defer {
                delegate.rewrite(commandBuilder)
            }.subscribeOn(scheduler)
}

/**
 * Registry for managing command builder rewriters.
 *
 * This registry allows registration and lookup of rewriters based on command types,
 * enabling dynamic modification of command builders before execution.
 *
 * @see CommandBuilderRewriter
 * @see SimpleCommandBuilderRewriterRegistry
 */
interface CommandBuilderRewriterRegistry {
    /**
     * Registers a command builder rewriter.
     *
     * @param rewriter the rewriter to register
     */
    fun register(rewriter: CommandBuilderRewriter)

    /**
     * Unregisters the rewriter for the specified command type.
     *
     * @param commandType the command type to unregister
     */
    fun unregister(commandType: Class<*>)

    /**
     * Gets the rewriter for the specified command type.
     *
     * @param commandType the command type to look up
     * @return the registered rewriter, or null if none found
     */
    fun getRewriter(commandType: Class<*>): CommandBuilderRewriter?
}

/**
 * Simple implementation of CommandBuilderRewriterRegistry.
 *
 * This implementation uses a concurrent hash map to store rewriters and
 * automatically wraps blocking rewriters with BlockingCommandBuilderRewriter.
 *
 * @see CommandBuilderRewriterRegistry
 * @see BlockingCommandBuilderRewriter
 */
class SimpleCommandBuilderRewriterRegistry : CommandBuilderRewriterRegistry {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val registrar = ConcurrentHashMap<Class<*>, CommandBuilderRewriter>()

    /**
     * Checks if the rewriter's rewrite method is annotated with @Blocking.
     *
     * @return true if the rewrite method has the @Blocking annotation
     * @see Blocking
     */
    private fun CommandBuilderRewriter.isBlocked(): Boolean {
        val rewriteFunction = javaClass.kotlin.functions.first {
            it.name == CommandBuilderRewriter::rewrite.name &&
                it.parameters[1].type.classifier == CommandBuilder::class
        }
        return rewriteFunction.scanAnnotation<Blocking>() != null
    }

    /**
     * Registers a command builder rewriter, automatically wrapping blocking rewriters.
     *
     * If the rewriter's rewrite method is annotated with @Blocking, it will be
     * wrapped with BlockingCommandBuilderRewriter for proper thread management.
     *
     * @param rewriter the rewriter to register
     * @see Blocking
     * @see BlockingCommandBuilderRewriter
     */
    override fun register(rewriter: CommandBuilderRewriter) {
        val blockableRewriter = if (rewriter.isBlocked()) {
            BlockingCommandBuilderRewriter(rewriter)
        } else {
            rewriter
        }
        val previous = registrar.put(blockableRewriter.supportedCommandType, blockableRewriter)
        log.info {
            "Register - supportedCommandType:[${blockableRewriter.supportedCommandType}] - previous:[$previous],current:[$blockableRewriter]."
        }
    }

    /**
     * Unregisters the rewriter for the specified command type.
     *
     * @param commandType the command type to unregister
     */
    override fun unregister(commandType: Class<*>) {
        val removed = registrar.remove(commandType)
        log.info {
            "Unregister - commandType:[$commandType] - removed:[$removed]."
        }
    }

    /**
     * Gets the registered rewriter for the specified command type.
     *
     * @param commandType the command type to look up
     * @return the registered rewriter, or null if none found
     */
    override fun getRewriter(commandType: Class<*>): CommandBuilderRewriter? = registrar[commandType]
}
