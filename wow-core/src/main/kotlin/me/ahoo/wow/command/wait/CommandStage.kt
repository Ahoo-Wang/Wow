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
        override fun shouldNotify(processingStage: CommandStage): Boolean {
            return this == processingStage
        }

        override fun isAfter(processingStage: CommandStage): Boolean {
            return false
        }
    },

    /**
     * 当命令被处理后，生成完成信号.
     */
    PROCESSED {
        override fun shouldNotify(processingStage: CommandStage): Boolean {
            return this == processingStage || SENT == processingStage
        }

        override fun isAfter(processingStage: CommandStage): Boolean {
            return SENT == processingStage
        }
    },

    /**
     * 当快照被生成后，生成完成信号.
     */
    SNAPSHOT {
        override fun shouldNotify(processingStage: CommandStage): Boolean {
            return this == processingStage ||
                SENT == processingStage ||
                PROCESSED == processingStage
        }

        override fun isAfter(processingStage: CommandStage): Boolean {
            return SENT == processingStage || PROCESSED == processingStage
        }
    },

    /**
     * 当命令产生的事件已被投影时，生成完成信号.
     */
    PROJECTED {
        override fun shouldNotify(processingStage: CommandStage): Boolean {
            return this == processingStage ||
                SENT == processingStage ||
                PROCESSED == processingStage
        }

        override fun isAfter(processingStage: CommandStage): Boolean {
            return SENT == processingStage || PROCESSED == processingStage
        }
    };

    abstract fun shouldNotify(processingStage: CommandStage): Boolean
    abstract fun isAfter(processingStage: CommandStage): Boolean
}
