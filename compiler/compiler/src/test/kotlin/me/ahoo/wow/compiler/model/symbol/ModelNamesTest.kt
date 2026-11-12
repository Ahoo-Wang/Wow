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

package me.ahoo.wow.compiler.model.symbol

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.SystemCommandGroups
import org.junit.jupiter.api.Test

/**
 * The compiler works on symbols and sees no `:spi`, so it names the system commands from `:api`'s registries: they are
 * pinned here to the registry the framework reads them by.
 */
class ModelNamesTest {
    @Test
    fun `the system commands every aggregate decides should be the registry's, by Kotlin name`() {
        ModelNames.SYSTEM_COMMANDS.assert()
            .isEqualTo(SystemCommandGroups.DECIDED_BY_EVERY_AGGREGATE.classes.map { it.canonicalName }.toSet())
        ModelNames.LIFECYCLE_COMMANDS.assert()
            .isEqualTo(SystemCommandGroups.Group.LIFECYCLE.commands.values.map { it.canonicalName }.toSet())
    }
}
