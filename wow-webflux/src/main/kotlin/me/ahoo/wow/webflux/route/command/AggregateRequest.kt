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

package me.ahoo.wow.webflux.route.command

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.infra.ifNotBlank
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.webflux.route.acceptsEventStream
import org.springframework.web.reactive.function.server.ServerRequest
import java.time.Duration
import java.util.*

fun ServerRequest.getLocalFirst(): Boolean? {
    headers().firstHeader(CommandHeaders.LOCAL_FIRST).ifNotBlank<String> {
        return it.toBoolean()
    }
    return null
}

fun ServerRequest.isSse(): Boolean {
    return acceptsEventStream()
}

fun ServerRequest.getWaitTimeout(default: Duration = DEFAULT_TIME_OUT): Duration {
    val waitTimeout = headers().firstHeader(CommandHeaders.WAIT_TIME_OUT)
        ?: headers().firstHeader(CommandHeaders.LEGACY_WAIT_TIME_OUT)
    return waitTimeout?.toLongOrNull()?.let {
        Duration.ofMillis(it)
    } ?: default
}

//region Wait Stage
fun ServerRequest.getWaitStage(): CommandStage {
    return headers().firstHeader(CommandHeaders.WAIT_STAGE).ifNotBlank { stage ->
        CommandStage.valueOf(stage.uppercase(Locale.getDefault()))
    } ?: CommandStage.PROCESSED
}

fun ServerRequest.getWaitContext(): String {
    return headers().firstHeader(CommandHeaders.WAIT_CONTEXT).orEmpty()
}

fun ServerRequest.getWaitProcessor(): String {
    return headers().firstHeader(CommandHeaders.WAIT_PROCESSOR).orEmpty()
}

fun ServerRequest.getWaitFunction(): String {
    return headers().firstHeader(CommandHeaders.WAIT_FUNCTION).orEmpty()
}

//endregion
//region Wait Chain Tail
fun ServerRequest.getWaitTailStage(): CommandStage? {
    return headers().firstHeader(CommandHeaders.WAIT_TAIL_STAGE).ifNotBlank { stage ->
        CommandStage.valueOf(stage.uppercase(Locale.getDefault()))
    }
}

fun ServerRequest.getWaitTailContext(): String {
    return headers().firstHeader(CommandHeaders.WAIT_TAIL_CONTEXT).orEmpty()
}

fun ServerRequest.getWaitTailProcessor(): String {
    return headers().firstHeader(CommandHeaders.WAIT_TAIL_PROCESSOR).orEmpty()
}

fun ServerRequest.getWaitTailFunction(): String {
    return headers().firstHeader(CommandHeaders.WAIT_TAIL_FUNCTION).orEmpty()
}
//endregion

fun ServerRequest.extractWaitPlan(commandMessage: CommandMessage<Any>): WaitPlan {
    val stage: CommandStage = getWaitStage()
    val waitContext = getWaitContext().ifBlank {
        commandMessage.contextName
    }
    val waitFunction = NamedFunctionInfoData(
        contextName = waitContext,
        processorName = getWaitProcessor(),
        name = getWaitFunction()
    )
    val waitTailStage = getWaitTailStage()
    if (stage == CommandStage.SAGA_HANDLED && waitTailStage != null) {
        val waitTailFunction = NamedFunctionInfoData(
            contextName = getWaitTailContext().ifBlank {
                commandMessage.contextName
            },
            processorName = getWaitTailProcessor(),
            name = getWaitTailFunction()
        )
        return CommandWait.chain(
            waitCommandId = commandMessage.commandId,
            function = waitFunction,
            tailStage = waitTailStage,
            tailFunction = waitTailFunction
        )
    }

    return CommandWait.stage(
        waitCommandId = commandMessage.commandId,
        stage = stage,
        contextName = waitContext,
        processorName = waitFunction.processorName,
        functionName = waitFunction.name
    )
}
