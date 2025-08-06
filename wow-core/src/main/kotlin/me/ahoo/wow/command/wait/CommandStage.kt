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

enum class CommandStage {
    /**
     * 当命令发布到命令总线/队列后，生成完成信号.
     */
    SENT {
        override val previous: List<CommandStage> = listOf()
    },

    /**
     * 当命令被聚合根处理完成后，生成完成信号.
     */
    PROCESSED {
        override val previous: List<CommandStage> = listOf(SENT)
    },

    /**
     * 当快照被生成后，生成完成信号.
     */
    SNAPSHOT {
        override val previous: List<CommandStage> = listOf(SENT, PROCESSED)
    },

    /**
     * 当命令产生的事件*投影*完成后，生成完成信号.
     */
    PROJECTED {
        override val previous: List<CommandStage> = listOf(SENT, PROCESSED)
        override val shouldWaitFunction: Boolean = true
    },

    /**
     * 当命令产生的事件被*事件处理器*处理完成后，生成完成信号.
     */
    EVENT_HANDLED {
        override val previous: List<CommandStage> = listOf(SENT, PROCESSED)
        override val shouldWaitFunction: Boolean = true
    },

    /**
     * 当命令产生的事件被*Saga*处理完成后，生成完成信号.
     */
    SAGA_HANDLED {
        override val previous: List<CommandStage> = listOf(SENT, PROCESSED)
        override val shouldWaitFunction: Boolean = true
    };

    /**
     * 前置阶段列表
     *
     * 定义了当前命令阶段之前必须完成的所有阶段。用于确定阶段之间的依赖关系和执行顺序。
     */
    abstract val previous: List<CommandStage>

    open val shouldWaitFunction: Boolean = false

    /**
     * 判断是否应该发送通知
     *
     * @param processingStage 处理阶段
     * @return 如果当前阶段等于或在处理阶段之后则返回true，否则返回false
     */
    fun shouldNotify(processingStage: CommandStage): Boolean {
        return this == processingStage || isPrevious(processingStage)
    }

    /**
     * 判断给定的处理阶段是否为当前阶段的前置阶段
     *
     * @param processingStage 待判断的处理阶段
     * @return 如果给定阶段是当前阶段的前置阶段则返回true，否则返回false
     */
    fun isPrevious(processingStage: CommandStage): Boolean {
        return processingStage in previous
    }
}

interface CommandStageCapable {
    val stage: CommandStage
}
