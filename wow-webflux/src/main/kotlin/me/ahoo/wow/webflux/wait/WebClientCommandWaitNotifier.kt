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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.command.wait.isLocalWaitStrategy
import me.ahoo.wow.messaging.handler.DEFAULT_RETRY_SPEC
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

class WebClientCommandWaitNotifier(
    private val waitStrategyRegistrar: WaitStrategyRegistrar,
    private val webClient: WebClient
) : CommandWaitNotifier {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> {
        return Mono.defer {
            if (isLocalWaitStrategy(waitSignal.id)) {
                log.debug {
                    "Notify Local - waitSignal: $waitSignal."
                }
                waitStrategyRegistrar.next(waitSignal)
                return@defer Mono.empty()
            }
            log.debug {
                "Notify remote: endpoint: [$commandWaitEndpoint] - waitSignal: $waitSignal."
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
