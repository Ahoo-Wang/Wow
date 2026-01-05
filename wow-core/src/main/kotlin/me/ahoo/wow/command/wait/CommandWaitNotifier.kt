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

package me.ahoo.wow.command.wait

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.id.GlobalIdGenerator
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * Interface for notifying command wait endpoints about processing results.
 * After command processors complete their work, they send results to waiting clients
 * through implementations of this interface.
 *
 * @author Ahoo Wang
 */
interface CommandWaitNotifier {
    /**
     * Sends a wait signal notification to the specified command wait endpoint.
     *
     * @param commandWaitEndpoint The endpoint address to notify.
     * @param waitSignal The signal containing processing result information.
     * @return A Mono that completes when the notification is sent.
     */
    fun notify(
        commandWaitEndpoint: String,
        waitSignal: WaitSignal
    ): Mono<Void>

    /**
     * Sends a wait signal notification asynchronously without waiting for completion.
     * Uses bounded elastic scheduler to avoid blocking the calling thread.
     *
     * @param commandWaitEndpoint The endpoint address to notify.
     * @param waitSignal The signal containing processing result information.
     */
    fun notifyAndForget(
        commandWaitEndpoint: String,
        waitSignal: WaitSignal
    ) {
        notify(commandWaitEndpoint, waitSignal)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe()
    }
}

/**
 * Local implementation of CommandWaitNotifier for in-process notifications.
 * This notifier forwards wait signals to registered wait strategies within the same JVM instance.
 *
 * @param waitStrategyRegistrar The registrar containing active wait strategies.
 */
class LocalCommandWaitNotifier(
    private val waitStrategyRegistrar: WaitStrategyRegistrar
) : CommandWaitNotifier {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    /**
     * Notifies local wait strategies if the signal belongs to this JVM instance.
     * Uses the wait strategy registrar to forward signals to waiting clients.
     *
     * @param commandWaitEndpoint The endpoint (ignored for local notifications).
     * @param waitSignal The signal to forward to local wait strategies.
     * @return A Mono that completes after attempting local notification.
     */
    override fun notify(
        commandWaitEndpoint: String,
        waitSignal: WaitSignal
    ): Mono<Void> =
        Mono.fromRunnable {
            if (isLocalWaitStrategy(waitSignal.id)) {
                log.debug {
                    "Notify Local - waitSignal: $waitSignal"
                }
                waitStrategyRegistrar.next(waitSignal)
            } else {
                log.warn {
                    "Ignore Notify - waitSignal: $waitSignal"
                }
            }
        }

    override fun notifyAndForget(
        commandWaitEndpoint: String,
        waitSignal: WaitSignal
    ) {
        notify(commandWaitEndpoint, waitSignal).subscribe()
    }
}

/**
 * Extension function to notify and forget using an extracted wait strategy.
 * Only sends notification if the wait strategy should be notified for this signal.
 *
 * @param waiteStrategy The extracted wait strategy containing endpoint and notification logic.
 * @param waitSignal The signal to potentially notify about.
 */
fun CommandWaitNotifier.notifyAndForget(
    waiteStrategy: ExtractedWaitStrategy,
    waitSignal: WaitSignal
) {
    if (!waiteStrategy.waitStrategy.shouldNotify(waitSignal)) {
        return
    }
    notifyAndForget(waiteStrategy.endpoint, waitSignal)
}

/**
 * Determines if a command wait ID belongs to the current JVM instance.
 * Uses the global ID generator to check if the machine ID in the wait ID
 * matches the current machine's ID.
 *
 * @param commandWaitId The wait command ID to check.
 * @return true if the wait ID belongs to this JVM instance, false otherwise.
 */
fun isLocalWaitStrategy(commandWaitId: String): Boolean {
    if (commandWaitId.isBlank()) {
        return false
    }
    return GlobalIdGenerator.stateParser.asState(commandWaitId).machineId == GlobalIdGenerator.machineId
}
