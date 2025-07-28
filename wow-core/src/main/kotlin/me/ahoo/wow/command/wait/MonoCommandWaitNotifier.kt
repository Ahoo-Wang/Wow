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
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.wait.SimpleWaitSignal.Companion.toWaitSignal
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.messaging.handler.MessageExchange
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Exceptions
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Mono
import reactor.util.context.Context

class MonoCommandWaitNotifier<E, M>(
    private val commandWaitNotifier: CommandWaitNotifier,
    private val processingStage: CommandStage,
    private val messageExchange: E,
    private val source: Mono<Void>
) : Mono<Void>() where E : MessageExchange<*, M>, M : Message<*, *>, M : CommandId, M : NamedBoundedContext {
    override fun subscribe(actual: CoreSubscriber<in Void>) {
        val message = messageExchange.message
        val waitStrategy = message.header.extractWaitStrategyInfo() ?: return source.subscribe(actual)
        if (!waitStrategy.shouldNotify(processingStage)) {
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

class CommandWaitNotifierSubscriber<E, M>(
    private val commandWaitNotifier: CommandWaitNotifier,
    private val processingStage: CommandStage,
    private val waitStrategy: WaitStrategy.Info,
    private val messageExchange: E,
    private val actual: CoreSubscriber<in Void>
) : BaseSubscriber<Void>() where E : MessageExchange<*, M>, M : Message<*, *>, M : CommandId, M : NamedBoundedContext {
    private val message = messageExchange.message
    private val isLastProjection = if (message is DomainEvent<*>) {
        message.isLast
    } else {
        false
    }

    override fun currentContext(): Context {
        return actual.currentContext()
    }

    override fun hookOnNext(value: Void) {
        // Mono<Void> will not call this method.
    }

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

    private fun notifySignal(errorInfo: ErrorInfo? = null) {
        val error = errorInfo ?: ErrorInfo.OK
        val functionInfo = messageExchange.getFunction()
            ?: FunctionInfoData.unknown(
                functionKind = FunctionKind.ERROR,
                contextName = messageExchange.message.contextName
            )

        val waitSignal = functionInfo.toWaitSignal(
            id = messageExchange.message.id,
            commandId = messageExchange.message.commandId,
            stage = processingStage,
            aggregateVersion = messageExchange.getAggregateVersion(),
            isLastProjection = isLastProjection,
            errorCode = error.errorCode,
            errorMsg = error.errorMsg,
            bindingErrors = error.bindingErrors,
            result = messageExchange.getCommandResult()
        )
        if (!waitStrategy.shouldNotify(waitSignal)) {
            return
        }
        commandWaitNotifier.notifyAndForget(waitStrategy.endpoint, waitSignal)
    }

    override fun hookOnComplete() {
        val errorInfo = messageExchange.getError()?.toErrorInfo()
        notifySignal(errorInfo)
        actual.onComplete()
    }
}

fun <E : MessageExchange<*, M>, M> Mono<Void>.thenNotifyAndForget(
    commandWaitNotifier: CommandWaitNotifier,
    processingStage: CommandStage,
    messageExchange: E
): Mono<Void> where M : Message<*, *>, M : CommandId, M : NamedBoundedContext {
    return MonoCommandWaitNotifier(
        commandWaitNotifier = commandWaitNotifier,
        processingStage = processingStage,
        messageExchange = messageExchange,
        source = this,
    )
}
