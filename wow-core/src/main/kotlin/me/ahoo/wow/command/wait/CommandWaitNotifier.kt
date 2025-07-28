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
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.COMMAND_WAIT_CONTEXT
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.COMMAND_WAIT_FUNCTION
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.COMMAND_WAIT_PROCESSOR
import me.ahoo.wow.command.wait.stage.WaitingForStage.Companion.COMMAND_WAIT_STAGE
import me.ahoo.wow.id.GlobalIdGenerator
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

const val COMMAND_WAIT_PREFIX = "command_wait_"
const val COMMAND_WAIT_ENDPOINT = "${COMMAND_WAIT_PREFIX}endpoint"

interface CommandWaitEndpoint {
    val endpoint: String
}

data class SimpleCommandWaitEndpoint(override val endpoint: String) : CommandWaitEndpoint

fun Header.extractWaitStrategy(): WaitStrategyInfo? {
    val commandWaitEndpoint = this[COMMAND_WAIT_ENDPOINT] ?: return null
    val stage = this[COMMAND_WAIT_STAGE].orEmpty()
    val context = this[COMMAND_WAIT_CONTEXT].orEmpty()
    val processor = this[COMMAND_WAIT_PROCESSOR].orEmpty()
    val function = this[COMMAND_WAIT_FUNCTION].orEmpty()
    return WaitingForInfo(
        commandWaitEndpoint = commandWaitEndpoint,
        stage = CommandStage.valueOf(stage),
        contextName = context,
        processorName = processor,
        functionName = function
    )
}

/**
 * 命令处理器完成处理后，将处理结果发往等待者
 * @author Ahoo Wang
 */
interface CommandWaitNotifier {
    fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void>

    fun notifyAndForget(commandWaitEndpoint: String, waitSignal: WaitSignal) {
        notify(commandWaitEndpoint, waitSignal)
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe()
    }
}

class LocalCommandWaitNotifier(
    private val waitStrategyRegistrar: WaitStrategyRegistrar
) : CommandWaitNotifier {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> {
        return Mono.fromRunnable {
            if (isLocalCommand(waitSignal.commandId)) {
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
    }
}

fun isLocalCommand(commandId: String): Boolean {
    if (commandId.isBlank()) {
        return false
    }
    return GlobalIdGenerator.stateParser.asState(commandId).machineId == GlobalIdGenerator.machineId
}
