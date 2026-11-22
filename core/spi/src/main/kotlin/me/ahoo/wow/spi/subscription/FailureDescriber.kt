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

package me.ahoo.wow.spi.subscription

import me.ahoo.wow.api.annotation.WowSpi

/**
 * Describes the failures of one technology (a driver, a codec, a client of another system) for the reason a parked
 * aggregate records (`ParkedAggregate.reason`), which an operator reads in the ops console, the ops routes and the
 * park webhook, and which leaves the service: a [FailureDescription] built from the failure's structured fields (an
 * error code, the name of an index, a constraint or a property, the type a value was read as), never from its message,
 * which may quote the value that failed. The full failure, its message and its stack trace, is in the node's log.
 *
 * The runtime asks, for the failure and then each of its causes, every describer in this order, and the first
 * description one gives is that failure's: the application's (the host's beans); then the modules' found on the
 * classpath (`ServiceLoader`, from a module's `META-INF/services/me.ahoo.wow.spi.subscription.FailureDescriber`), each
 * knowing its own technology's failures (`wow-mongo` MongoDB's, `wow-postgres` JDBC's, `wow-kafka` Kafka's); then the
 * runtime's own and the built-in ones (Jackson's). A failure none describes is recorded by its class name alone. A
 * describer that throws describes nothing.
 *
 * A describer is called on the parking path, once per failure parked: it decides from the exception alone, quickly and
 * without blocking.
 */
@WowSpi
fun interface FailureDescriber {
    /** A value-free description of [failure], or null when this describer does not know it. */
    fun describe(failure: Throwable): FailureDescription?

    companion object {
        /** Describes nothing. */
        @JvmField
        val NONE: FailureDescriber = FailureDescriber { null }
    }
}
