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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The Manifest fixtures ([ManifestFixtures]) are what `manifest-tools` writes, so the reader is tested on the
 * generator's own output: each is a byte-for-byte copy of the `QueryKeywordsTest` snapshot of the same name. The build
 * gives the snapshots' directory as `wow.manifest-tools.snapshots`, an input of this test task.
 */
class ManifestFixturesTest {
    @Test
    fun `each Manifest fixture should be a byte-for-byte copy of its manifest-tools snapshot`() {
        val snapshots = Path.of(checkNotNull(System.getProperty("wow.manifest-tools.snapshots")))
        val fixtures = FIXTURES.listDirectoryEntries()
        fixtures.assert().isNotEmpty()
        fixtures.forEach { fixture ->
            val snapshot = snapshots.resolve(fixture.name)
            snapshot.exists().assert().describedAs("$fixture copies no manifest-tools snapshot: $snapshot").isTrue()
            fixture.readText().assert()
                .describedAs("$fixture is no longer a copy of $snapshot: copy it again.")
                .isEqualTo(snapshot.readText())
        }
    }

    private companion object {
        val FIXTURES: Path = Path.of("src", "test", "resources", "manifest")
    }
}
