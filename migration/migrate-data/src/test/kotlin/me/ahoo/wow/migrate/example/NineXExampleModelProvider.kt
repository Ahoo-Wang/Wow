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

package me.ahoo.wow.migrate.example

import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider

/**
 * The binding index of the 9.x example's V10 port, as the compiler would generate it for its domain module, and
 * registered as a service in the test resources: what the command line finds on the class path to rebuild states.
 */
internal class NineXExampleModelProvider : ModelProvider {
    override val context: String = NineXExample.CONTEXT

    override val binding: Int = ModelProvider.BINDING
    override val fragment: String = "META-INF/wow/manifest/ninex-example.json"

    override fun models(dependencies: Dependencies): List<AggregateModel<*>> =
        listOf(NineXExample.orderModel, NineXExample.cartModel)
}
