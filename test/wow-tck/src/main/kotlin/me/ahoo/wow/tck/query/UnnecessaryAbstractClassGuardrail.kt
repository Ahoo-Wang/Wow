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

package me.ahoo.wow.tck.query

import me.ahoo.test.asserts.assert
import java.io.File
import java.util.jar.JarFile
import kotlin.reflect.KClass

/**
 * Detekt's `UnnecessaryAbstractClass` needs type resolution, which the `detekt` task does not run, so the query
 * modules keep the same rule with this list-style check: an abstract class whose members, declared or inherited, are
 * all concrete should be a concrete class or an `object`.
 *
 * [assertNone] scans the compiled classes of [anchor]'s module under [packageName]. [allowed] names the public base
 * classes that stay abstract so that users subclass rather than instantiate them; it can only shrink: a new
 * violation fails, and so does an allowed class that no longer is one.
 */
class UnnecessaryAbstractClassGuardrail(
    private val anchor: KClass<*>,
    private val packageName: String,
    private val allowed: Set<String> = emptySet(),
) {
    fun assertNone() {
        val found = violations()
        val unexpected = found - allowed
        unexpected.assert()
            .describedAs("Abstract classes with no abstract member; make them concrete or an object: $unexpected")
            .isEmpty()
        val stale = allowed - found
        stale.assert().describedAs("Remove from the allowlist, no longer a violation: $stale").isEmpty()
    }

    fun violations(): Set<String> =
        classNames()
            .map { Class.forName(it, false, anchor.java.classLoader) }
            .filter { it.isKotlinClass() && !it.isInterface && !it.isAnonymousClass && !it.isSynthetic }
            .map { it.kotlin }
            .filter { it.isAbstract && it.members.none { member -> member.isAbstract } }
            .mapNotNull { it.qualifiedName }
            .toSortedSet()

    private fun classNames(): List<String> {
        val location = File(anchor.java.protectionDomain.codeSource.location.toURI())
        val prefix = packageName.replace('.', '/') + "/"
        val entries = if (location.isDirectory) {
            location.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(location).invariantSeparatorsPath }
                .toList()
        } else {
            JarFile(location).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        return entries
            .filter { it.startsWith(prefix) && it.endsWith(CLASS_SUFFIX) }
            .map { it.removeSuffix(CLASS_SUFFIX).replace('/', '.') }
    }

    private fun Class<*>.isKotlinClass(): Boolean =
        getAnnotation(Metadata::class.java)?.kind == KOTLIN_CLASS_KIND

    private companion object {
        const val CLASS_SUFFIX = ".class"
        const val KOTLIN_CLASS_KIND = 1
    }
}
