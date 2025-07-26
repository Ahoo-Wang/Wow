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
    },

    /**
     * 当命令产生的事件被*事件处理器*处理完成后，生成完成信号.
     */
    EVENT_HANDLED {
        override val previous: List<CommandStage> = listOf(SENT, PROCESSED)
    },

    /**
     * 当命令产生的事件被*Saga*处理完成后，生成完成信号.
     */
    SAGA_HANDLED {
        override val previous: List<CommandStage> = listOf(SENT, PROCESSED)
    };

    /**
     * 前置阶段
     */
    abstract val previous: List<CommandStage>

    /**
     * 判断是否应该发送通知
     *
     * @param processingStage 处理阶段
     * @return 如果当前阶段等于或在处理阶段之后则返回true，否则返回false
     */
    fun shouldNotify(processingStage: CommandStage): Boolean {
        return this == processingStage || isAfter(processingStage)
    }

    /**
     * 判断当前处理阶段是否在指定处理阶段之后
     *
     * @param processingStage 要比较的处理阶段
     * @return 如果当前阶段在指定阶段之后则返回true，否则返回false
     */
    fun isAfter(processingStage: CommandStage): Boolean {
        return processingStage in previous
    }
}
