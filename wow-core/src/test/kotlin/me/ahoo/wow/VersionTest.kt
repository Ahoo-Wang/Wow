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

package me.ahoo.wow

import me.ahoo.wow.api.Version
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class VersionTest {

    @Test
    fun initialized() {
        val version = object : Version {
            override val version: Int
                get() = Version.UNINITIALIZED_VERSION
        }
        assertThat(version.initialized, equalTo(false))
    }

    @Test
    fun initializedGivenInitial() {
        val version = object : Version {
            override val version: Int
                get() = Version.INITIAL_VERSION
        }
        assertThat(version.initialized, equalTo(true))
    }
}
