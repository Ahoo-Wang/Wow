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

import me.ahoo.wow.api.annotation.OnStateEvent
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.compensation.api.ExecutionFailedApplied
import me.ahoo.wow.compensation.api.ExecutionFailedCreated
import me.ahoo.wow.compensation.api.ExecutionSuccessApplied
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.server.webhook.TemplateEngine
import me.ahoo.wow.compensation.server.webhook.weixin.WeiXinSendMessage.Companion.markdown
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@me.ahoo.wow.api.annotation.StatelessSaga
class WeiXinWebHook(
    private val hookProperties: WeiXinWebHookProperties,
    private val webclientBuilder: WebClient.Builder
) {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(WeiXinWebHook::class.java)
    }

    private val webClient = webclientBuilder.build()

    @Retry(false)
    @OnStateEvent
    fun onExecutionFailedCreated(
        event: DomainEvent<ExecutionFailedCreated>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        if (!hookProperties.events.contains(HookEvent.EXECUTION_FAILED_CREATED)) {
            return Mono.empty()
        }
        val sendMessage = TemplateEngine.renderOnEvent(event, state).markdown()
        return sendMessage(sendMessage)
    }

    @Retry(false)
    @OnStateEvent
    fun onExecutionFailedApplied(
        event: DomainEvent<ExecutionFailedApplied>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        if (!hookProperties.events.contains(HookEvent.EXECUTION_FAILED_APPLIED)) {
            return Mono.empty()
        }
        val sendMessage = TemplateEngine.renderOnEvent(event, state).markdown()
        return sendMessage(sendMessage)
    }

    @Retry(false)
    @OnStateEvent
    fun onExecutionSuccessApplied(
        event: DomainEvent<ExecutionSuccessApplied>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        if (!hookProperties.events.contains(HookEvent.EXECUTION_SUCCESS_APPLIED)) {
            return Mono.empty()
        }
        val sendMessage = TemplateEngine.renderOnEvent(event, state).markdown()
        return sendMessage(sendMessage)
    }

    @Retry(false)
    @OnStateEvent
    fun onCompensationPrepared(
        event: DomainEvent<CompensationPrepared>,
        state: ReadOnlyStateAggregate<IExecutionFailedState>
    ): Mono<Void> {
        if (!hookProperties.events.contains(HookEvent.COMPENSATION_PREPARED)) {
            return Mono.empty()
        }
        val sendMessage = TemplateEngine.renderOnEvent(event, state).markdown()
        return sendMessage(sendMessage)
    }

    private fun sendMessage(sendMessage: WeiXinSendMessage): Mono<Void> {
        return webClient.post()
            .uri(hookProperties.url)
            .bodyValue(sendMessage)
            .retrieve()
            .bodyToMono(WeiXinSendResult::class.java)
            .flatMap { result ->
                if (!result.isSuccess()) {
                    if (log.isErrorEnabled) {
                        log.error("Send message failed. result: $result")
                    }
                }
                Mono.empty()
            }
    }
}