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

package me.ahoo.wow.infra

import reactor.core.Exceptions

/**
 * Adds [error] as suppressed unless it is this throwable itself or already suppressed by it. Synchronized on this
 * throwable, so concurrent failures of one shared error do not race.
 */
internal fun Throwable.addSuppressedIfAbsent(error: Throwable) {
    if (this === error) {
        return
    }
    synchronized(this) {
        if (suppressedExceptions.none { suppressed -> suppressed === error }) {
            addSuppressed(error)
        }
    }
}

/**
 * Keeps the first failure: returns [next] when there is no failure yet, otherwise this failure with [next] suppressed.
 */
internal fun Throwable?.mergeFailure(next: Throwable): Throwable {
    if (this == null) {
        return next
    }
    addSuppressedIfAbsent(next)
    return this
}

/**
 * Runs [action] for every element even when some fail, then throws the first failure with the later ones suppressed.
 * A fatal error ([Exceptions.throwIfFatal]) propagates at once.
 */
@Suppress("TooGenericExceptionCaught")
internal fun <T> Iterable<T>.forEachThenThrow(action: (T) -> Unit) {
    var failure: Throwable? = null
    for (element in this) {
        try {
            action(element)
        } catch (error: Throwable) {
            Exceptions.throwIfFatal(error)
            failure = failure.mergeFailure(error)
        }
    }
    failure?.let { throw it }
}
