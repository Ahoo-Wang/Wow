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

package me.ahoo.wow.test

import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier

/** A failure of a technology only [ModuleFailures] knows, as a module's driver would throw one. */
class ModuleFailure(message: String) : IllegalStateException(message)

/**
 * A module's classifier, found on this test classpath (`META-INF/services/me.ahoo.wow.spi.subscription.FailureClassifier`)
 * as `wow-postgres`'s and `wow-client`'s are on an application's: it takes its technology's [ModuleFailure] for
 * unrecoverable.
 */
class ModuleFailures : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? =
        FailureClass.UNRECOVERABLE.takeIf { failure is ModuleFailure }
}
