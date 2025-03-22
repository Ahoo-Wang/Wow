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

package me.ahoo.wow.messaging.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.naming.Named
import org.reactivestreams.Subscription
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.SignalType

abstract class SafeSubscriber<T : Any> : BaseSubscriber<T>(), Named {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Suppress("TooGenericExceptionCaught")
    override fun hookOnNext(value: T) {
        log.debug {
            "[$name] OnNext $value."
        }
        try {
            safeOnNext(value)
        } catch (e: Throwable) {
            log.error(e) {
                "[$name] OnNext handle error."
            }
            safeOnNextError(value, throwable = e)
        }
    }

    open fun safeOnNext(value: T) {}
    open fun safeOnNextError(value: T, throwable: Throwable) {}

    override fun hookOnSubscribe(subscription: Subscription) {
        log.debug {
            "[$name] OnSubscribe."
        }
        super.hookOnSubscribe(subscription)
    }

    override fun hookOnError(throwable: Throwable) {
        log.error(throwable) {
            "[$name] OnError."
        }
    }

    override fun hookFinally(type: SignalType) {
        log.info {
            "[$name] Finally $type."
        }
    }
}
