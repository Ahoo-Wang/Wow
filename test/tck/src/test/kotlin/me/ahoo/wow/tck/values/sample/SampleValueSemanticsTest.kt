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

package me.ahoo.wow.tck.values.sample

import me.ahoo.wow.tck.values.ValueSemanticsSpec

/** The spec as a module's test extends it, over the sample classes, with an exclusion of each kind. */
class SampleValueSemanticsTest : ValueSemanticsSpec() {
    override val packages: List<String> = listOf("me.ahoo.wow.tck.values.sample")

    override val excluded: Map<String, String> = mapOf("me.ahoo.wow.tck.values.sample.*#kind" to "a constant")

    override val excludedClasses: Map<String, String> = mapOf(Identity::class.java.name to "an identity")
}
