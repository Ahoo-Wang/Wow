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

import me.ahoo.cosid.cosid.CosIdGenerator
import me.ahoo.cosid.cosid.CosIdIdStateParser
import me.ahoo.cosid.cosid.CosIdState
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.exception.WowException
import me.ahoo.wow.infra.Decorator
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Global Id Generator
 */
object GlobalIdGenerator : CosIdGenerator, Decorator<CosIdGenerator> {
    private val log = LoggerFactory.getLogger(GlobalIdGenerator::class.java)

    override val delegate: CosIdGenerator by lazy {
        return@lazy loadGlobalIdGenerator() ?: throw NotInitializedGlobalIdGeneratorError()
    }

    private fun loadGlobalIdGenerator(): CosIdGenerator? {
        return ServiceLoader.load(GlobalIdGeneratorFactory::class.java)
            .sortedByOrder()
            .firstNotNullOfOrNull {
                if (log.isInfoEnabled) {
                    log.info("Load $it to create GlobalIdGenerator.")
                }
                val idGenerator = it.create()
                if (idGenerator == null) {
                    if (log.isInfoEnabled) {
                        log.info("$it create GlobalIdGenerator is null.")
                    }
                } else {
                    if (log.isInfoEnabled) {
                        log.info("Setup $idGenerator to GlobalIdGenerator.")
                    }
                }
                idGenerator
            }
    }

    override fun getMachineId(): Int {
        return delegate.machineId
    }

    override fun getLastTimestamp(): Long {
        return delegate.lastTimestamp
    }

    override fun getStateParser(): CosIdIdStateParser {
        return delegate.stateParser
    }

    override fun generateAsState(): CosIdState {
        return delegate.generateAsState()
    }
}

class NotInitializedGlobalIdGeneratorError :
    WowException("NotInitializedGlobalIdGenerator", "GlobalIdGenerator is not initialized.")
