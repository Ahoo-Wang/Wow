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

package me.ahoo.wow.infra.batch

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Sinks

class BatchLifecycleTest {
    @Test
    fun `close should reject new emissions and drain results`() {
        val lifecycle = BatchLifecycle("test")

        lifecycle.terminalErrorOrClosed().assert().isNull()
        lifecycle.emitIfOpen { Sinks.EmitResult.OK }
            .assert()
            .isEqualTo(Sinks.EmitResult.OK)

        lifecycle.initiateClose().assert().isTrue()
        lifecycle.initiateClose().assert().isFalse()
        lifecycle.emitIfOpen { Sinks.EmitResult.OK }
            .assert()
            .isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
        lifecycle.terminalErrorOrClosed()
            .assert()
            .isInstanceOf(BatchClosedException::class.java)
        lifecycle.processorCompleted()
            .assert()
            .isSameAs(BatchLifecycle.ProcessorCompletion.DrainResults)
        lifecycle.resultDispatcherTerminated()
            .assert()
            .isSameAs(BatchLifecycle.ResultDrainCompletion.Closed)
        lifecycle.resultDispatcherTerminated()
            .assert()
            .isSameAs(BatchLifecycle.ResultDrainCompletion.Closed)
        lifecycle.fail(IllegalStateException("late"))
            .assert()
            .isSameAs(BatchLifecycle.FailureTransition.Closed)
    }

    @Test
    fun `failure should preserve the first terminal cause`() {
        val lifecycle = BatchLifecycle("test")
        val firstFailure = IllegalStateException("first")
        val secondFailure = IllegalStateException("second")

        val installed = lifecycle.fail(firstFailure)
        installed.assert()
            .isInstanceOf(BatchLifecycle.FailureTransition.Installed::class.java)
        (installed as BatchLifecycle.FailureTransition.Installed)
            .cause
            .assert()
            .isSameAs(firstFailure)
        lifecycle.isFailed.assert().isTrue()
        lifecycle.failureCause.assert().isSameAs(firstFailure)
        lifecycle.terminalErrorOrClosed().assert().isSameAs(firstFailure)

        val existing = lifecycle.fail(secondFailure)
        existing.assert()
            .isInstanceOf(BatchLifecycle.FailureTransition.Existing::class.java)
        (existing as BatchLifecycle.FailureTransition.Existing)
            .cause
            .assert()
            .isSameAs(firstFailure)

        val processorCompletion = lifecycle.processorCompleted()
        processorCompletion.assert()
            .isInstanceOf(BatchLifecycle.ProcessorCompletion.Failed::class.java)
        (processorCompletion as BatchLifecycle.ProcessorCompletion.Failed)
            .cause
            .assert()
            .isSameAs(firstFailure)

        val resultCompletion = lifecycle.resultDispatcherTerminated()
        resultCompletion.assert()
            .isInstanceOf(BatchLifecycle.ResultDrainCompletion.Failed::class.java)
        (resultCompletion as BatchLifecycle.ResultDrainCompletion.Failed)
            .cause
            .assert()
            .isSameAs(firstFailure)
    }

    @Test
    fun `processor may complete before an explicit close`() {
        val lifecycle = BatchLifecycle("test")

        lifecycle.processorCompleted()
            .assert()
            .isSameAs(BatchLifecycle.ProcessorCompletion.DrainResults)
        lifecycle.resultDispatcherTerminated()
            .assert()
            .isSameAs(BatchLifecycle.ResultDrainCompletion.Closed)
    }

    @Test
    fun `premature result dispatcher termination should fail lifecycle`() {
        val lifecycle = BatchLifecycle("test")

        val completion = lifecycle.resultDispatcherTerminated()
        completion.assert()
            .isInstanceOf(BatchLifecycle.ResultDrainCompletion.Failed::class.java)
        val failure = completion as BatchLifecycle.ResultDrainCompletion.Failed
        failure.cause.message
            .assert()
            .isEqualTo("Batch result dispatcher[test] terminated before the processor.")
        lifecycle.failureCause.assert().isSameAs(failure.cause)

        val closingLifecycle = BatchLifecycle("closing")
        closingLifecycle.initiateClose().assert().isTrue()
        closingLifecycle.resultDispatcherTerminated()
            .assert()
            .isInstanceOf(BatchLifecycle.ResultDrainCompletion.Failed::class.java)
    }
}
