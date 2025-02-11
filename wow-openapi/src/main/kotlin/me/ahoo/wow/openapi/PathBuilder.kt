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

package me.ahoo.wow.openapi

private const val PATH_SEPARATOR = "/"

class PathBuilder {
    private val segments = StringBuilder()
    fun append(segment: String): PathBuilder {
        if (segment.isBlank()) {
            return this
        }
        if (segment.startsWith(PATH_SEPARATOR)) {
            segments.append(segment)
            return this
        }
        segments.append(PATH_SEPARATOR).append(segment)
        return this
    }

    fun build(): String {
        return segments.toString()
    }
}
