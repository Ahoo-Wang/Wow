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

package me.ahoo.wow.webflux.wait

import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.command.wait.isLocalCommand
import me.ahoo.wow.messaging.handler.DEFAULT_RETRY_SPEC
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

class WebClientCommandWaitNotifier(
    private val waitStrategyRegistrar: WaitStrategyRegistrar,
    private val webClient: WebClient,
) : CommandWaitNotifier {
    companion object {
        private val log = LoggerFactory.getLogger(WebClientCommandWaitNotifier::class.java)
    }

    override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> {
        return Mono.defer {
            if (isLocalCommand(waitSignal.commandId)) {
                if (log.isDebugEnabled) {
                    log.debug(
                        "Notify Local - waitSignal: {}",
                        waitSignal,
                    )
                }
                waitStrategyRegistrar.next(waitSignal)
                return@defer Mono.empty()
            }
            if (log.isDebugEnabled) {
                log.debug(
                    "Notify remote: endpoint: [{}] - waitSignal: {}",
                    commandWaitEndpoint,
                    waitSignal,
                )
            }
            webClient
                .post()
                .uri(commandWaitEndpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(waitSignal)
                .retrieve()
                .bodyToMono(Void::class.java)
                .retryWhen(DEFAULT_RETRY_SPEC)
        }
    }
}
