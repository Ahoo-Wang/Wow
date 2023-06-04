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
package me.ahoo.wow.messaging

import me.ahoo.wow.api.messaging.Header

/**
 * Default Header implementation.
 *
 * @author ahoo wang
 */
class DefaultHeader(private val delegate: MutableMap<String, String> = mutableMapOf<String, String>()) :
    Header, MutableMap<String, String> by delegate {
    companion object {
        fun empty(): Header {
            return DefaultHeader()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DefaultHeader) return false
        return delegate == other.delegate
    }

    override fun hashCode(): Int {
        return delegate.hashCode()
    }

    override fun toString(): String {
        return "DefaultHeader(delegate=$delegate)"
    }
}

fun Map<String, String>?.asHeader(): Header {
    if (isNullOrEmpty()) {
        return DefaultHeader.empty()
    }
    if (this is Header) {
        return this
    }
    if (this is MutableMap<*, *>) {
        return DefaultHeader(this as MutableMap<String, String>)
    }
    return DefaultHeader(this.toMutableMap())
}
