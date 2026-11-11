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
import me.ahoo.wow.spring.boot.node.ShutdownTimeoutEnvironmentPostProcessor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/**
 * A setting the node's stop bound reads that its options refuse (`wow.wait.*`, `wow.cluster.*`, `wow.subscription.*`):
 * the host refuses to start as for any refused setting of the node, naming the group of keys and the option's reason
 * in Spring Boot's failure analysis. The shutdown phase's post-processor ([ShutdownTimeoutEnvironmentPostProcessor])
 * leaves it to the node's assembly, after Spring Boot configured its logging.
 */
@ExtendWith(OutputCaptureExtension::class)
class StopBoundRefusalTest {
    private fun refused(setting: Pair<String, Any>, output: CapturedOutput, group: String, reason: String) {
        val failure = assertThrows<Exception> { startHost(setting) }
        val refusal = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<WowConfigurationException>()
            .single()
        refusal.key.assert().isEqualTo(group)
        refusal.reason.assert().contains(reason)
        output.out.assert().contains("APPLICATION FAILED TO START", "Property: $group", reason)
        // Reported through the logging Spring Boot configured for the application (its name in each line), not before.
        output.out.assert().containsPattern("ERROR .*--- \\[bank-service] .*LoggingFailureAnalysisReporter")
    }

    @Test
    fun `a wait maximum that is not positive should be refused naming wow-wait`(output: CapturedOutput) {
        refused("wow.wait.max-timeout" to "0s", output, "wow.wait", "The maximum wait timeout is positive.")
    }

    @Test
    fun `a wait default above the maximum should be refused naming wow-wait`(output: CapturedOutput) {
        // The hosts' tests run with a 15 s maximum (wow.wait.max-timeout).
        val reason = "The default wait timeout 2m is above the maximum 15s: raise the maximum or lower the default."
        refused("wow.wait.default-timeout" to "2m", output, "wow.wait", reason)
    }

    @Test
    fun `a drain timeout past the reconcile timeout should be refused naming wow-cluster`(output: CapturedOutput) {
        refused("wow.cluster.drain-timeout" to "5m", output, "wow.cluster", "must be longer than the drain timeout")
    }

    @Test
    fun `a negative subscription stop timeout should be refused naming wow-subscription`(output: CapturedOutput) {
        val reason = "The stop timeout is never negative."
        refused("wow.subscription.stop-timeout" to "-1s", output, "wow.subscription", reason)
    }
}
