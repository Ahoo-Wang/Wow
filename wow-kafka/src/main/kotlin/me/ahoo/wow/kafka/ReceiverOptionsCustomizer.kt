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

package me.ahoo.wow.kafka

import reactor.kafka.receiver.ReceiverOptions
import reactor.util.context.Context
import reactor.util.context.ContextView
import kotlin.jvm.optionals.getOrNull

fun interface ReceiverOptionsCustomizer {
    fun customize(receiverOptions: ReceiverOptions<String, String>): ReceiverOptions<String, String>
}

object NoOpReceiverOptionsCustomizer : ReceiverOptionsCustomizer {
    override fun customize(receiverOptions: ReceiverOptions<String, String>): ReceiverOptions<String, String> {
        return receiverOptions
    }
}

fun ContextView.getReceiverOptionsCustomizer(): ReceiverOptionsCustomizer? {
    return getOrEmpty<ReceiverOptionsCustomizer>(ReceiverOptionsCustomizer::class.java.name).getOrNull()
}

fun Context.setReceiverOptionsCustomizer(receiverOptionsCustomizer: ReceiverOptionsCustomizer): Context {
    return this.put(ReceiverOptionsCustomizer::class.java.name, receiverOptionsCustomizer)
}
