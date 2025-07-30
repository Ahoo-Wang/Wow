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

package me.ahoo.wow.command.wait.chain

import me.ahoo.wow.api.messaging.processor.ProcessorInfo
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandStageCapable

interface WaitingNode : CommandStageCapable, NamedBoundedContext {

    data class Sent(
        override val contextName: String,
        override val aggregateName: String,
        /**
         * command name
         */
        override val name: String
    ) : WaitingNode, NamedAggregate, Named {
        override val stage: CommandStage
            get() = CommandStage.SENT
    }

    data class Processed(
        override val contextName: String,
        override val processorName: String
    ) : WaitingNode, ProcessorInfo {
        override val stage: CommandStage
            get() = CommandStage.PROCESSED
    }

    data class Snapshot(
        override val contextName: String,
        override val aggregateName: String,
    ) : WaitingNode, NamedAggregate {
        override val stage: CommandStage
            get() = CommandStage.SNAPSHOT
    }

    data class Projected(
        override val contextName: String,
        override val aggregateName: String,
        override val processorName: String,
        override val functionName: String
    ) : WaitingNode, NamedAggregate, {
        override val stage: CommandStage
            get() = CommandStage.PROJECTED
    }
}