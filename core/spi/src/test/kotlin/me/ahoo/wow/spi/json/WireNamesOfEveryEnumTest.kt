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

package me.ahoo.wow.spi.json

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.spi.transport.EnvelopeKind
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The wire name of every constant of every enumeration the api, query-api and spi modules define, held to the fixture
 * [WireNames] wrote before its names were kept once per constant: the cache must not change one name. Every Wow
 * enumeration that reaches the wire through [WowEnums] or a direct [WireNames.of] is one of these modules', a
 * domain's own being never respelled. The modules' compiled classes are walked, so a new enumeration joins the fixture
 * at its next regeneration.
 *
 * Regenerate the fixture, never edit it by hand:
 * `./gradlew :spi:test --tests "*WireNamesOfEveryEnumTest" --rerun -Dwow-test.snapshot.update=true`.
 */
class WireNamesOfEveryEnumTest {
    private val fixture: Path = Path.of("src", "test", "resources", "wire-names", "every-enum.txt")

    /** The enumerations of the modules [anchors] are compiled in: each anchor's class root, a directory or a jar. */
    private fun enumerationsOf(vararg anchors: Class<*>): List<Class<*>> =
        anchors.map { File(it.protectionDomain.codeSource.location.toURI()) }.distinct().flatMap { root ->
            val names = if (root.isDirectory) {
                Files.walk(root.toPath()).use { files ->
                    files.filter { it.toString().endsWith(".class") }
                        .map { root.toPath().relativize(it).toString() }.toList()
                }
            } else {
                JarFile(root).use { jar -> jar.entries().toList().map { it.name }.filter { it.endsWith(".class") } }
            }
            names.map { it.removeSuffix(".class").replace(File.separatorChar, '.').replace('/', '.') }
        }.mapNotNull { runCatching { Class.forName(it, false, javaClass.classLoader) }.getOrNull() }
            .filter { it.isEnum && it.name.startsWith("me.ahoo.wow.") }
            .sortedBy { it.name }

    private fun render(): String = enumerationsOf(
        WireNames::class.java,
        FilterOperator::class.java,
        EnvelopeKind::class.java,
    ).joinToString("") { type ->
        type.enumConstants.joinToString("") { "${type.name}.${(it as Enum<*>).name}=${WireNames.of(it)}\n" }
    }

    @Test
    fun `every enumeration's wire names should be the ones WireNames wrote before it kept them once`() {
        val actual = render()
        if (System.getProperty("wow-test.snapshot.update").equals("true", ignoreCase = true)) {
            Files.createDirectories(fixture.parent)
            fixture.writeText(actual)
        }
        fixture.readText().assert()
            .describedAs(
                "Regenerate $fixture only for a new enumeration or constant: an existing name must not change.",
            )
            .isEqualTo(actual)
    }
}
