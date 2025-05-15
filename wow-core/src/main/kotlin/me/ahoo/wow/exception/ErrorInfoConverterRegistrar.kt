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

package me.ahoo.wow.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

object ErrorInfoConverterRegistrar {
    private val log = KotlinLogging.logger {}
    private val registrar = ConcurrentHashMap<Class<out Throwable>, ErrorInfoConverter<Throwable>>()

    fun register(throwableClass: Class<out Throwable>, errorInfoConverter: ErrorInfoConverter<Throwable>) {
        log.info { "Register ErrorInfoConverter: $errorInfoConverter" }
        registrar[throwableClass] = errorInfoConverter
    }

    fun unregister(throwableClass: Class<out Throwable>) {
        log.info { "Unregister ErrorInfoConverter: $throwableClass" }
        registrar.remove(throwableClass)
    }

}