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

package me.ahoo.wow.cosid

import me.ahoo.cosid.runtime.RuntimeSpec

/**
 * wow-cosid's settings, `wow.cosid.*`: the one default of each key, and the class its configuration metadata comes
 * from. The module imports no Spring Boot, so [WowCosIdAutoConfiguration] reads its key from Spring's
 * `Environment`, falling back to the default here; the host knows the key from the metadata this module ships.
 */
class CosIdProperties {
    /**
     * The CosId generator of the node's global ids (command ids, wait ids), by its name in `cosid.generators`: CosId's
     * `default` generator when not set. It must be one of CosId's generators, or the node does not start; a `cosid80`
     * generator with a radix-62 codec gives 15-character ids that sort like their time.
     */
    var globalGenerator: String = RuntimeSpec.DEFAULT_GENERATOR

    companion object {
        const val PREFIX: String = "wow.cosid"

        /** The key of [globalGenerator]. */
        const val GLOBAL_GENERATOR: String = "wow.cosid.global-generator"
    }
}
