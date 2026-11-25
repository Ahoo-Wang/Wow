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

package me.ahoo.wow.gradle

import me.ahoo.test.asserts.assert
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The TestKit builds of this module's tests run in a Gradle user home of their own, whose daemons do not watch the file
 * system. A test edits a build script a moment before its next build; a watcher's event arriving after that build
 * started let the daemon take the script as unchanged and reuse the old configuration cache entry (#6718).
 */
class TestKitHomeFunctionalTest {
    @Test
    fun `the TestKit builds should not watch the file system, in a Gradle user home of this module's own`(
        @TempDir root: File,
    ) {
        System.getProperty("org.gradle.testkit.dir").assert().endsWith("testkit-home")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"home\"")
        GradleRunner.create().withProjectDir(root).withArguments("help", "--info").build().output.assert()
            .contains("Watching the file system is configured to be disabled")
    }
}
