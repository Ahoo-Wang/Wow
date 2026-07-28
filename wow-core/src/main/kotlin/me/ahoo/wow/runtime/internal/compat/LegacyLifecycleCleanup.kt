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

package me.ahoo.wow.runtime.internal.compat

import me.ahoo.wow.infra.lifecycle.ForceStoppable
import me.ahoo.wow.infra.lifecycle.GracefullyStoppable
import me.ahoo.wow.runtime.internal.RuntimeCleanupExecutor
import reactor.core.Exceptions

/**
 * Preserves graceful-only legacy resources under a runtime hard-stop boundary.
 *
 * New resources should implement [ForceStoppable]. The fallback is deliberately
 * best-effort and runs on the bounded process cleanup executor so legacy
 * `stopGracefully` construction or subscription cannot block the force caller.
 */
@Suppress("TooGenericExceptionCaught")
internal fun GracefullyStoppable.forceStopOrScheduleGracefulCleanup() {
    if (this is ForceStoppable) {
        forceStop()
        return
    }
    check(
        RuntimeCleanupExecutor.execute {
            try {
                stopGracefully().subscribe({}, {})
            } catch (error: Throwable) {
                Exceptions.throwIfFatal(error)
            }
        },
    ) {
        "Unable to schedule legacy graceful cleanup for " +
            "[${javaClass.name}@${System.identityHashCode(this).toString(16)}] because the " +
            "process-wide runtime cleanup executor is saturated."
    }
}
