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

package me.ahoo.wow.migrate

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

class ComparisonFingerprintTest {
    @TempDir
    lateinit var directory: Path

    private fun config(root: Path = directory) = MigrationConfig(
        map = root.resolve("migration-map.json").toString(),
        manifests = listOf(root.resolve("manifest.json").toString()),
        source = SourceConfig("mongodb://localhost", "nine_x"),
        target = TargetConfig(type = "postgres", jdbcUrl = "jdbc:postgresql://localhost/v10"),
        classpath = listOf(root.resolve("lib").toString()),
    )

    private fun fingerprint(
        config: MigrationConfig = config(),
        sample: Int = 100,
        zone: ZoneId = ZoneId.of("UTC"),
        tool: Path? = directory.resolve("tool"),
    ) = ComparisonFingerprint.of(config, sample, zone, tool)

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun files(root: Path) {
        write(root.resolve("migration-map.json"), "{}")
        write(root.resolve("manifest.json"), "{}")
        write(root.resolve("lib/models.jar"), "models")
        write(root.resolve("lib/classes/State.class"), "state")
        write(root.resolve("tool/migrate-data.jar"), "tool")
    }

    @Test
    fun `the same inputs give the same fingerprint, wherever they are`() {
        files(directory)
        val first = fingerprint()
        fingerprint().assert().isEqualTo(first).hasSize(64)
        val elsewhere = directory.resolve("elsewhere")
        files(elsewhere)
        fingerprint(config(elsewhere), tool = elsewhere.resolve("tool")).assert().isEqualTo(first)
    }

    @Test
    fun `a changed map, model, sample, time zone or tool gives another fingerprint`() {
        files(directory)
        val first = fingerprint()
        fingerprint(sample = 10).assert().isNotEqualTo(first)
        fingerprint(zone = ZoneId.of("Asia/Shanghai")).assert().isNotEqualTo(first)
        fingerprint(tool = null).assert().isNotEqualTo(first)
        write(directory.resolve("lib/classes/State.class"), "state, changed")
        val model = fingerprint()
        model.assert().isNotEqualTo(first)
        write(directory.resolve("migration-map.json"), "{ }")
        fingerprint().assert().isNotEqualTo(model)
        Files.delete(directory.resolve("manifest.json"))
        fingerprint().assert().isNotEqualTo(model)
    }

    @Test
    fun `the tool's own code counts by default`() {
        files(directory)
        ComparisonFingerprint.of(config(), 100).assert().isNotEqualTo(fingerprint(tool = null))
    }
}
