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

package me.ahoo.wow.id

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosid.CosId
import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.cosid.provider.IdGeneratorProvider
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order

/**
 * Factory for creating global [CosIdGenerator] instances using the CosId library.
 *
 * This factory attempts to retrieve a [CosIdGenerator] from the [IdGeneratorProvider] using a predefined ID name.
 * The ID name is determined by the system property "wow.cosid" or defaults to [CosId.COSID].
 *
 * The factory is ordered with [ORDER_FIRST] to prioritize it when loading global ID generators.
 *
 * @property idProvider the [IdGeneratorProvider] used to retrieve ID generators. Defaults to [DefaultIdGeneratorProvider.INSTANCE].
 * @see GlobalIdGeneratorFactory
 * @see CosIdGenerator
 */
@Order(ORDER_FIRST)
class CosIdGlobalIdGeneratorFactory(
    private val idProvider: IdGeneratorProvider = DefaultIdGeneratorProvider.INSTANCE
) : GlobalIdGeneratorFactory {
    companion object {
        private val log = KotlinLogging.logger {}
        const val ID_KEY = "wow.cosid"
        val ID_NAME: String = System.getProperty(ID_KEY, CosId.COSID)
    }

    /**
     * Creates a global [CosIdGenerator] instance.
     *
     * Attempts to retrieve a [CosIdGenerator] from the [idProvider] using the predefined [ID_NAME].
     * If no generator is found, returns null. Otherwise, returns the found generator cast to [CosIdGenerator].
     *
     * @return the [CosIdGenerator] if found in the provider, null otherwise
     */
    override fun create(): CosIdGenerator? {
        val idGenOp = idProvider.get(ID_NAME)
        if (idGenOp.isEmpty) {
            log.info {
                "Create - Not found Id name[$ID_NAME] from DefaultIdGeneratorProvider."
            }
            return null
        }
        val idGenerator = idGenOp.get()
        log.info {
            "Create - Found Id name[$ID_NAME] [$idGenerator] from DefaultIdGeneratorProvider."
        }
        return idGenerator as CosIdGenerator
    }
}
