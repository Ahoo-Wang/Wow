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
import me.ahoo.wow.api.exception.asErrorInfo
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.messaging.handler.MessageExchange
import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Exceptions
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Mono
import reactor.util.context.Context

class MonoCommandWaitNotifier<M>(
    private val commandWaitNotifier: CommandWaitNotifier,
    private val processingStage: CommandStage,
    private val messageExchange: M,
    private val source: Mono<Void>,
) : Mono<Void>() where M : MessageExchange<*> {
    override fun subscribe(actual: CoreSubscriber<in Void>) {
        val message = messageExchange.message
        if (message !is CommandId) {
            return source.subscribe(actual)
        }
        val waitStrategy = message.extractWaitStrategy() ?: return source.subscribe(actual)
        if (!waitStrategy.stage.shouldNotify(processingStage)) {
            return source.subscribe(actual)
        }

        source.subscribe(
            CommandWaitNotifierSubscriber(
                commandWaitNotifier,
                processingStage,
                waitStrategy,
                message.commandId,
                messageExchange,
                actual,
            ),
        )
    }
}

class CommandWaitNotifierSubscriber<M>(
    private val commandWaitNotifier: CommandWaitNotifier,
    private val processingStage: CommandStage,
    private val waitStrategy: WaitStrategyInfo,
    private val commandId: String,
    private val messageExchange: M,
    private val actual: CoreSubscriber<in Void>,
) : BaseSubscriber<Void>() where M : MessageExchange<*> {
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

        actual.onError(exception)
        val errorInfo = exception.asErrorInfo()
        notifySignal(errorInfo)
    }

    private fun notifySignal(errorInfo: ErrorInfo? = null) {
        val waitSignal = errorInfo?.let {
            SimpleWaitSignal(
                commandId = commandId,
                stage = processingStage,
                isLastProjection = isLastProjection,
                errorCode = errorInfo.errorCode,
                errorMsg = errorInfo.errorMsg,
            )
        } ?: SimpleWaitSignal(
            commandId = commandId,
            stage = processingStage,
            isLastProjection = isLastProjection,
        )
        commandWaitNotifier.notifyAndForget(waitStrategy.commandWaitEndpoint, waitSignal)
    }

    override fun hookOnComplete() {
        actual.onComplete()
        val errorInfo = if (messageExchange is ServerCommandExchange<*>) {
            messageExchange.getError()?.asErrorInfo()
        } else {
            null
        }
        notifySignal(errorInfo)
    }
}

fun <M : MessageExchange<*>> Mono<Void>.thenNotifyAndForget(
    commandWaitNotifier: CommandWaitNotifier,
    processingStage: CommandStage,
    messageExchange: M,
): Mono<Void> {
    return MonoCommandWaitNotifier(
        commandWaitNotifier = commandWaitNotifier,
        processingStage = processingStage,
        messageExchange = messageExchange,
        source = this
    )
}
