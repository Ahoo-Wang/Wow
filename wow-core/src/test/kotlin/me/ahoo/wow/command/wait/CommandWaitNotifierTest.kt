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

class CommandWaitNotifierTest {
    @Test
    fun `default notifyAndForget subscribes to notification`() {
        val recordingNotifier = RecordingCommandWaitNotifier()
        val notifier: CommandWaitNotifier = recordingNotifier
        val signal = testSignal(CommandStage.PROCESSED)

        notifier.notifyAndForget(TEST_ENDPOINT, signal)

        recordingNotifier.notifications.assert().containsExactly(
            RecordingCommandWaitNotifier.Notification(TEST_ENDPOINT, signal)
        )
    }

    @Test
    fun `extracted plan notification is skipped when signal should not notify target`() {
        val notifier = RecordingCommandWaitNotifier()
        val waitPlan = ExtractedWaitPlan(
            endpoint = TEST_ENDPOINT,
            waitCommandId = "wait-command-id",
            plan = CommandWait.processed("wait-command-id"),
        )

        notifier.notifyAndForget(waitPlan, testSignal(CommandStage.PROJECTED))

        notifier.notifications.assert().isEmpty()
    }
}
