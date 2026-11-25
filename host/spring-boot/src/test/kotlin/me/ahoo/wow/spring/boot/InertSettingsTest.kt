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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.config.WowConfigurationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/**
 * A setting that would start and do nothing refuses the start, naming its key: a code under `wow.` the framework does
 * not answer, a standard request fact listed, a ledger published to a name no context has, an adaptive floor above its
 * ceiling. A code of the application's that no Manifest declares may still be right, and starts with a warning.
 */
@ExtendWith(OutputCaptureExtension::class)
class InertSettingsTest {
    private fun refusal(vararg properties: Pair<String, Any>): WowConfigurationException {
        val failure = assertThrows<Exception> { startHost(*properties).close() }
        return generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>().first()
    }

    @Test
    fun `a code under wow the framework does not answer should refuse the start`() {
        refusal("wow.http.errors.[wow.command.bussy].status" to 503).key.assert()
            .isEqualTo("wow.http.errors.[wow.command.bussy]")
    }

    @Test
    fun `a code no Manifest declares should start with one warning naming its key, a declared one with none`(
        output: CapturedOutput,
    ) {
        startHost(
            "wow.http.errors.[$FROZEN].status" to 409,
            "wow.http.errors.[bank.account.frozn].status" to 409,
        ).close()
        output.out.assert().containsOnlyOnce("wow.http.errors.[bank.account.frozn] names a code")
            .doesNotContain("wow.http.errors.[$FROZEN] names a code")
    }

    @Test
    fun `a standard request fact listed should refuse the start`() {
        refusal("wow.http.request-facts" to "X-Device,remote_ip").also { refused ->
            refused.key.assert().isEqualTo("wow.http.request-facts")
            refused.value.assert().isEqualTo("remote_ip")
        }
    }

    @Test
    fun `a ledger published to no context name should refuse the start`() {
        refusal("wow.ops.publish.context" to "Ops").also { refused ->
            refused.key.assert().isEqualTo("wow.ops.publish.context")
            refused.value.assert().isEqualTo("Ops")
        }
    }

    @Test
    fun `an adaptive floor above its ceiling should refuse the start`() {
        refusal("wow.query.adaptive.ceiling" to 4, "wow.query.adaptive.floor" to 5).key.assert()
            .isEqualTo("wow.query.adaptive.floor")
    }
}
