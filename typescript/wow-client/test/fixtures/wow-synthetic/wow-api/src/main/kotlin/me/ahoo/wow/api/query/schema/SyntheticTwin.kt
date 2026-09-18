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

// Not Wow source. The same simple name as an enum in the parent package: the
// checker must not let one declaration quietly overwrite the other.
package me.ahoo.wow.api.query.schema

enum class SyntheticTwin { ONE, TWO }

// The path of a constant in the parent package, with another value.
object SyntheticProtocol {
    object Group {
        const val SHARED = "shared-here"
    }
}
