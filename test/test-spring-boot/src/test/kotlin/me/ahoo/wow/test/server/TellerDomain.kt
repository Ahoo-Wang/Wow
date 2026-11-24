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

package me.ahoo.wow.test.server

import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.kernel.aggregate

const val TELLER = "teller"

data class OpenTill(val teller: String = "")

data class TillOpened(val teller: String)

class Till {
    var teller: String = ""
}

/**
 * A second domain beside the quickstart's bank, written by hand as the compiler generates it, with its fragment,
 * `META-INF/wow/manifest/test-server-teller.json`: no build names its service, so a local run starts it under a name
 * of its own, as the second service of one JVM. The quickstart's build names the bank's service `bank-service`, which
 * the bank runs under or refuses to start.
 */
class TellerModels : ModelProvider {
    override val context: String = TELLER
    override val binding: Int = ModelProvider.BINDING
    override val fragment: String = "META-INF/wow/manifest/test-server-teller.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> = listOf(
        aggregate<Till>(TELLER, "till") {
            initial { Till() }
            lifecycle()
            decide<OpenTill>(Creation.REQUIRED) { _, body -> Decision.events(TillOpened(body.teller)) }
            evolve<TillOpened> { teller = it.teller }
        },
    )
}
