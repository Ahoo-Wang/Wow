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

package me.ahoo.wow.client

import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException

/**
 * The raw network failures of the JDK an application's handler meets calling another system itself: a socket that
 * could not connect or was reset ([SocketException]: refused, no route, reset, broken pipe), a host name that did not
 * resolve ([UnknownHostException]) and an HTTP connection that timed out ([HttpConnectTimeoutException]) are
 * [FailureClass.SYSTEMIC], the downstream down for every aggregate. Any other is left to the other classifiers. This
 * module's own clients throw none of these: they answer theirs as `wow.peer.unavailable`, which the kernel classifies.
 *
 * Found by `ServiceLoader`, from this module's `META-INF/services/me.ahoo.wow.spi.subscription.FailureClassifier`:
 * without the module, these failures are recoverable.
 */
internal class NetworkFailureClassifier : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? = when (failure) {
        is SocketException, is UnknownHostException, is HttpConnectTimeoutException -> FailureClass.SYSTEMIC
        else -> null
    }
}
