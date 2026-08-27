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

package me.ahoo.wow.compensation.server.webhook.weixin

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.EventProcessor
import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.ExecutionFailedApplied
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionSuccessApplied
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.api.RecoverableMarked
import me.ahoo.wow.compensation.server.configuration.CompensationProperties
import me.ahoo.wow.compensation.server.webhook.TemplateEngine
import me.ahoo.wow.compensation.server.webhook.weixin.HookEvent.Companion.toHookEvent
import me.ahoo.wow.compensation.server.webhook.weixin.client.WeiXinBotApi
import me.ahoo.wow.compensation.server.webhook.weixin.client.WeiXinSendMessage
import me.ahoo.wow.compensation.server.webhook.weixin.client.WeiXinSendMessage.Companion.markdown
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import reactor.core.publisher.Mono

@ConditionalOnWeiXinWebHookEnabled
@EventProcessor
class WeiXinWebHook(
    private val compensationProperties: CompensationProperties,
    private val hookProperties: WeiXinWebHookProperties,
    private val weiXinBotApi: WeiXinBotApi
) {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    @Retry(false)
    @OnStateEvent
    fun onExecutionFailedCreated(
        event: DomainEvent<ExecutionFailedCreated>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        return sendMessage(event, state)
    }

    @Retry(false)
    @OnStateEvent
    fun onExecutionFailedApplied(
        event: DomainEvent<ExecutionFailedApplied>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        return sendMessage(event, state)
    }

    @Retry(false)
    @OnStateEvent
    fun onExecutionSuccessApplied(
        event: DomainEvent<ExecutionSuccessApplied>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        return sendMessage(event, state)
    }

    @Retry(false)
    @OnStateEvent
    fun onCompensationPrepared(
        event: DomainEvent<CompensationPrepared>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        return sendMessage(event, state)
    }

    @Retry(false)
    @OnStateEvent
    fun onRecoverableMarked(
        event: DomainEvent<RecoverableMarked>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        return sendMessage(event, state)
    }

    private fun sendMessage(event: DomainEvent<*>, state: ReadOnlyStateAggregate<IExecutionFailedState>): Mono<Void> {
        val currentEvent = event.name.toHookEvent()
        if (!hookProperties.events.contains(currentEvent)) {
            log.info {
                "Skip send message. event: $currentEvent"
            }
            return Mono.empty()
        }
        val sendMessage = TemplateEngine.renderOnEvent(event, state, compensationProperties.host).markdown()
        return sendMessage(sendMessage)
    }

    private fun sendMessage(sendMessage: WeiXinSendMessage): Mono<Void> {
        return weiXinBotApi.sendMessage(sendMessage)
            .flatMap { result ->
                if (!result.isSuccess()) {
                    log.error {
                        "Send message failed. result: $result"
                    }
                }
                Mono.empty()
            }
    }
}
