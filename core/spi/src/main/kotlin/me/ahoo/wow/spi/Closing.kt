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

package me.ahoo.wow.spi

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The one rule of closing what an owner opened: in reverse order of opening, every one even when another fails to
 * close, the first failure thrown once all were tried with the later ones suppressed by it. The host closes the stores
 * it opened by it, and a store the ports it opened.
 */
@InternalWowApi
object Closing {
    /** Closes [closeables], given in the order they were opened, in reverse, as the rule says. */
    fun inReverse(closeables: Collection<AutoCloseable>) {
        var failure: Exception? = null
        closeables.reversed().forEach { closeable ->
            try {
                closeable.close()
            } catch (@Suppress("TooGenericExceptionCaught") failed: Exception) {
                failure?.addSuppressed(failed) ?: run { failure = failed }
            }
        }
        failure?.let { throw it }
    }

    /**
     * What [block] gives; when it fails, [closeables] (those opened so far, in order) are closed ([inReverse]) and its
     * failure thrown as it was, with any failure to close suppressed by it.
     */
    inline fun <T> onFailure(closeables: Collection<AutoCloseable>, block: () -> T): T = try {
        block()
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        try {
            inReverse(closeables)
        } catch (@Suppress("TooGenericExceptionCaught") failed: Exception) {
            failure.addSuppressed(failed)
        }
        throw failure
    }
}
