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

package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandWait
import org.junit.jupiter.api.Test
import java.time.Duration

class WaitDeadlineTest {
    @Test
    fun `a plan without a function names the stage only`() {
        CommandWait.processed("wait-command-id").waited().assert().isEqualTo("stage [PROCESSED]")
    }

    @Test
    fun `a function with no names adds nothing`() {
        CommandWait.projected("wait-command-id", "example").waited().assert().isEqualTo("stage [PROJECTED]")
    }

    @Test
    fun `a function names what it has`() {
        CommandWait.projected("wait-command-id", "example", functionName = "onOrderCreated")
            .waited()
            .assert()
            .isEqualTo("stage [PROJECTED] of function [onOrderCreated]")
        CommandWait.projected("wait-command-id", "example", "OrderProjector", "onOrderCreated")
            .waited()
            .assert()
            .isEqualTo("stage [PROJECTED] of function [OrderProjector.onOrderCreated]")
    }

    @Test
    fun `the message says what was waited for and how long`() {
        waitTimeoutMessage("stage [SNAPSHOT]", Duration.ofSeconds(3))
            .assert()
            .isEqualTo("Waiting for stage [SNAPSHOT] timed out after 3000ms.")
    }
}
