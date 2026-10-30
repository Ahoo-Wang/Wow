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

/**
 * A read a peer's typed read API cannot serve as the code asked it: a state class no contract has, a ref of
 * another type. A mistake of the code that reads, not of the caller it reads for, so a subclass: a decide function that
 * makes it fails its command, as an unexpected exception does, rather than rejecting it as invalid (decision D314
 * answers only the exact [IllegalArgumentException] as a rejection).
 */
internal class ReadMisuse(message: String) : IllegalArgumentException(message)

/** Throws a [ReadMisuse] of [message] unless [value] holds: `require` for the read API's arguments. */
internal inline fun requireRead(value: Boolean, message: () -> String) {
    if (!value) throw ReadMisuse(message())
}
