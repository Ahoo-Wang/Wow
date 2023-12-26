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

package me.ahoo.wow.infra.prepare

import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.prepare.PreparedValue.Companion.toForever
import reactor.core.publisher.Mono

interface PrepareKey<V : Any> : Named {

    fun prepare(key: String, value: V): Mono<Boolean> {
        return prepare(key, value.toForever())
    }

    fun prepare(key: String, value: PreparedValue<V>): Mono<Boolean>

    fun get(key: String): Mono<V> {
        return getValue(key)
            .filter { !it.isExpired }
            .map { it.value }
    }

    fun getValue(key: String): Mono<PreparedValue<V>>

    fun rollback(key: String): Mono<Boolean>

    /**
     * Rollback only if both key and value match
     */
    fun rollback(key: String, value: V): Mono<Boolean>

    fun reprepare(key: String, oldValue: V, newValue: V): Mono<Boolean> {
        return reprepare(key, oldValue, newValue.toForever())
    }

    fun reprepare(key: String, oldValue: V, newValue: PreparedValue<V>): Mono<Boolean>

    fun reprepare(key: String, value: V): Mono<Boolean> {
        return reprepare(key, value.toForever())
    }

    fun reprepare(key: String, value: PreparedValue<V>): Mono<Boolean>

    fun reprepare(oldKey: String, oldValue: V, newKey: String, newValue: V): Mono<Boolean> {
        return reprepare(oldKey, oldValue, newKey, newValue.toForever())
    }

    fun reprepare(oldKey: String, oldValue: V, newKey: String, newValue: PreparedValue<V>): Mono<Boolean> {
        require(oldKey != newKey) {
            "oldKey must not be equals to newKey. oldKey:[$oldKey]"
        }
        return usingPrepare(newKey, newValue) { prepared ->
            if (!prepared) {
                return@usingPrepare Mono.just(false)
            }
            rollback(oldKey, oldValue).doOnNext {
                if (!it) {
                    throw IllegalStateException(
                        "Reprepare - Rollback failed. newKey:[$newKey] oldKey:[$oldKey],oldValue:[$oldValue]"
                    )
                }
            }
        }
    }

    fun <R> usingPrepare(key: String, value: V, then: (Boolean) -> Mono<R>): Mono<R> {
        return usingPrepare(key, value.toForever(), then)
    }

    fun <R> usingPrepare(key: String, value: PreparedValue<V>, then: (Boolean) -> Mono<R>): Mono<R> {
        return prepare(key, value)
            .flatMap { prepared ->
                then(prepared).onErrorResume {
                    val errorMono = Mono.error<R>(it)
                    if (!prepared) {
                        return@onErrorResume errorMono
                    }
                    rollback(key, value.value)
                        .then(errorMono)
                }
            }
    }
}
