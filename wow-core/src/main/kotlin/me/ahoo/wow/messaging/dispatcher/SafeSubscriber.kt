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

import me.ahoo.wow.api.naming.Named
import org.reactivestreams.Subscription
import org.slf4j.LoggerFactory
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.SignalType

abstract class SafeSubscriber<T : Any> : BaseSubscriber<T>(), Named {
    companion object {
        private val log = LoggerFactory.getLogger(SafeSubscriber::class.java)
    }

    override fun hookOnNext(value: T) {
        if (log.isDebugEnabled) {
            log.debug("[$name] OnNext $value")
        }
        try {
            safeOnNext(value)
        } catch (e: Throwable) {
            if (log.isErrorEnabled) {
                log.error("[$name] OnNext handle error.", e)
            }
            safeOnNextError(value, throwable = e)
        }
    }

    open fun safeOnNext(value: T) {}
    open fun safeOnNextError(value: T, throwable: Throwable) {}

    override fun hookOnSubscribe(subscription: Subscription) {
        if (log.isDebugEnabled) {
            log.debug("[$name] OnSubscribe.")
        }
        super.hookOnSubscribe(subscription)
    }

    override fun hookOnError(throwable: Throwable) {
        if (log.isErrorEnabled) {
            log.error("[$name] OnError.", throwable)
        }
    }

    override fun hookFinally(type: SignalType) {
        if (log.isDebugEnabled) {
            log.debug("[$name] Finally $type.")
        }
    }
}
