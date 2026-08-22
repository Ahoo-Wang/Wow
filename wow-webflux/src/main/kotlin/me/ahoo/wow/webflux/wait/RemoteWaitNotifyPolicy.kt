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

import me.ahoo.wow.messaging.handler.DEFAULT_RETRY_SPEC
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.util.retry.Retry

internal fun Throwable.isRetryableRemoteWaitFailure(): Boolean = when (this) {
    is WebClientRequestException -> true
    is WebClientResponseException ->
        statusCode.is5xxServerError ||
            statusCode == HttpStatus.REQUEST_TIMEOUT ||
            statusCode == HttpStatus.TOO_MANY_REQUESTS

    else -> false
}

private val DEFAULT_REMOTE_WAIT_NOTIFY_RETRY: Retry =
    DEFAULT_RETRY_SPEC.modifyErrorFilter { current ->
        current.or(Throwable::isRetryableRemoteWaitFailure)
    }

class RemoteWaitNotifyPolicy(
    val retry: Retry = DEFAULT_REMOTE_WAIT_NOTIFY_RETRY,
    val scheduler: Scheduler = Schedulers.immediate()
) {
    fun <T : Any> apply(publisher: Mono<T>): Mono<T> = publisher.retryWhen(retry).subscribeOn(scheduler)
}
