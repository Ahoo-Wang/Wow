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

package me.ahoo.wow.runtime.subscription

import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier

/**
 * How the subscriptions of a runtime classify their handlers' failures: the kernel's classification
 * ([FailureClassification], the one the test DSL classifies with too), the application's classifiers first, then the
 * modules' on the classpath ([FailureClassification.MODULES]), then what only the runtime throws ([ExecutorFailures]),
 * then the built-in rules.
 */
internal object RuntimeFailures {
    /** The runtime's own classification, with no application classifier. */
    val DEFAULT: FailureClassification by lazy { of() }

    /** The [application]'s classifiers, then the modules', then the runtime's own. */
    fun of(application: List<FailureClassifier> = emptyList()): FailureClassification =
        FailureClassification.of(application, own = listOf(ExecutorFailures))
}

/**
 * What only the runtime throws: a command the node's own executor refused as busy ([CommandBusyException]) is
 * [FailureClass.BACKPRESSURE], a downstream that is up but overloaded, as a store that did not admit a call is.
 */
internal object ExecutorFailures : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? =
        FailureClass.BACKPRESSURE.takeIf { failure is CommandBusyException }
}
