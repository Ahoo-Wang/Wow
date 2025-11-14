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

package me.ahoo.wow.command.wait

import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.wait.SimpleWaitSignal.Companion.toWaitSignal
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.saga.stateless.getCommandStream
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Exceptions
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Mono
import reactor.util.context.Context

/**
 * A Mono wrapper that automatically notifies wait strategies when command processing completes.
 * This class intercepts the completion of a Mono<Void> operation and sends appropriate
 * wait signals to registered wait strategies based on the processing stage and message exchange.
 *
 * @param E The type of message exchange.
 * @param M The type of message in the exchange.
 * @param commandWaitNotifier The notifier used to send wait signals.
 * @param processingStage The command processing stage being notified.
 * @param messageExchange The message exchange containing processing context.
 * @param source The original Mono<Void> operation to wrap.
 */
class MonoCommandWaitNotifier<E, M>(
    private val commandWaitNotifier: CommandWaitNotifier,
    private val processingStage: CommandStage,
    private val messageExchange: E,
    private val source: Mono<Void>
) : Mono<Void>() where E : MessageExchange<*, M>, M : Message<*, *>, M : CommandId, M : NamedBoundedContext, M : AggregateIdCapable {
    override fun subscribe(actual: CoreSubscriber<in Void>) {
        val message = messageExchange.message
        val waitStrategy = message.header.extractWaitStrategy() ?: return source.subscribe(actual)
        if (!waitStrategy.waitStrategy.shouldNotify(processingStage)) {
            return source.subscribe(actual)
        }

        source.subscribe(
            CommandWaitNotifierSubscriber(
                commandWaitNotifier = commandWaitNotifier,
                processingStage = processingStage,
                waitStrategy = waitStrategy,
                messageExchange = messageExchange,
                actual = actual,
            ),
        )
    }
}

/**
 * Subscriber that handles command processing completion and sends wait notifications.
 * This subscriber wraps the actual subscriber and intercepts completion/error events
 * to send appropriate wait signals to waiting clients.
 *
 * @param E The type of message exchange.
 * @param M The type of message in the exchange.
 * @param commandWaitNotifier The notifier for sending wait signals.
 * @param processingStage The stage of processing being completed.
 * @param waitStrategy The extracted wait strategy containing notification details.
 * @param messageExchange The message exchange with processing context.
 * @param actual The actual subscriber to delegate completion events to.
 */
class CommandWaitNotifierSubscriber<E, M>(
    private val commandWaitNotifier: CommandWaitNotifier,
    private val processingStage: CommandStage,
    private val waitStrategy: ExtractedWaitStrategy,
    private val messageExchange: E,
    private val actual: CoreSubscriber<in Void>
) : BaseSubscriber<Void>() where E : MessageExchange<*, M>, M : Message<*, *>, M : CommandId, M : NamedBoundedContext, M : AggregateIdCapable {
    private val message = messageExchange.message
    private val isLastProjection =
        if (message is DomainEvent<*>) {
            message.isLast
        } else {
            false
        }

    override fun currentContext(): Context = actual.currentContext()

    /**
     * Mono<Void> will not call this method.
     */
    override fun hookOnNext(value: Void) = Unit

    override fun hookOnSubscribe(subscription: Subscription) {
        actual.onSubscribe(this)
    }

    override fun hookOnError(throwable: Throwable) {
        val exception = if (Exceptions.isRetryExhausted(throwable)) {
            requireNotNull(throwable.cause)
        } else {
            throwable
        }
        val errorInfo = exception.toErrorInfo()
        notifySignal(errorInfo)
        actual.onError(exception)
    }

    private fun getCommands(): List<String> {
        if (processingStage != CommandStage.SAGA_HANDLED) {
            return emptyList()
        }
        val domainEventExchange = messageExchange as DomainEventExchange<*>
        return domainEventExchange.getCommandStream()?.map { it.commandId }.orEmpty()
    }

    private fun notifySignal(errorInfo: ErrorInfo? = null) {
        val error = errorInfo ?: ErrorInfo.OK
        val functionInfo = messageExchange.getFunction()
            ?: FunctionInfoData.unknown(
                functionKind = FunctionKind.ERROR,
                contextName = messageExchange.message.contextName
            )

        val waitSignal = functionInfo.toWaitSignal(
            id = messageExchange.message.id,
            waitCommandId = waitStrategy.waitCommandId,
            commandId = messageExchange.message.commandId,
            aggregateId = messageExchange.message.aggregateId,
            stage = processingStage,
            aggregateVersion = messageExchange.getAggregateVersion(),
            isLastProjection = isLastProjection,
            errorCode = error.errorCode,
            errorMsg = error.errorMsg,
            bindingErrors = error.bindingErrors,
            result = messageExchange.getCommandResult(),
            commands = getCommands()
        )
        commandWaitNotifier.notifyAndForget(waitStrategy, waitSignal)
    }

    override fun hookOnComplete() {
        val errorInfo = messageExchange.getError()?.toErrorInfo()
        notifySignal(errorInfo)
        actual.onComplete()
    }
}

/**
 * Extension function that wraps a Mono<Void> to automatically notify wait strategies on completion.
 * This provides a convenient way to add wait notification behavior to any Mono<Void> operation
 * in the command processing pipeline.
 *
 * @param E The type of message exchange.
 * @param M The type of message in the exchange.
 * @param commandWaitNotifier The notifier for sending wait signals.
 * @param processingStage The processing stage to notify about.
 * @param messageExchange The message exchange containing context information.
 * @return A new Mono that will send notifications when the original Mono completes.
 */
fun <E : MessageExchange<*, M>, M> Mono<Void>.thenNotifyAndForget(
    commandWaitNotifier: CommandWaitNotifier,
    processingStage: CommandStage,
    messageExchange: E
): Mono<Void> where M : Message<*, *>, M : CommandId, M : NamedBoundedContext, M : AggregateIdCapable =
    MonoCommandWaitNotifier(
        commandWaitNotifier = commandWaitNotifier,
        processingStage = processingStage,
        messageExchange = messageExchange,
        source = this,
    )
