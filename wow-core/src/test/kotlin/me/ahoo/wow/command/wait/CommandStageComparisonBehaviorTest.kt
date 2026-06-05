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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class CommandStageComparisonBehaviorTest {

    @Test
    fun `stages expose current production dependency graph`() {
        CommandStage.SENT.previous.assert().isEmpty()
        CommandStage.PROCESSED.previous.assert().containsExactly(CommandStage.SENT)
        CommandStage.SNAPSHOT.previous.assert().containsExactly(CommandStage.SENT, CommandStage.PROCESSED)
        CommandStage.PROJECTED.previous.assert().containsExactly(CommandStage.SENT, CommandStage.PROCESSED)
        CommandStage.EVENT_HANDLED.previous.assert().containsExactly(CommandStage.SENT, CommandStage.PROCESSED)
        CommandStage.SAGA_HANDLED.previous.assert().containsExactly(CommandStage.SENT, CommandStage.PROCESSED)
    }

    @Test
    fun `should notify for same stage or prerequisite stage only`() {
        CommandStage.PROCESSED.shouldNotify(CommandStage.SENT).assert().isTrue()
        CommandStage.PROCESSED.shouldNotify(CommandStage.PROCESSED).assert().isTrue()
        CommandStage.PROCESSED.shouldNotify(CommandStage.PROJECTED).assert().isFalse()

        CommandStage.PROJECTED.shouldNotify(CommandStage.PROCESSED).assert().isTrue()
        CommandStage.PROJECTED.shouldNotify(CommandStage.SNAPSHOT).assert().isFalse()
        CommandStage.SENT.shouldNotify(CommandStage.PROCESSED).assert().isFalse()
    }

    @Test
    fun `only post event stages wait for function matching`() {
        CommandStage.SENT.shouldWaitFunction.assert().isFalse()
        CommandStage.PROCESSED.shouldWaitFunction.assert().isFalse()
        CommandStage.SNAPSHOT.shouldWaitFunction.assert().isFalse()
        CommandStage.PROJECTED.shouldWaitFunction.assert().isTrue()
        CommandStage.EVENT_HANDLED.shouldWaitFunction.assert().isTrue()
        CommandStage.SAGA_HANDLED.shouldWaitFunction.assert().isTrue()
    }
}
