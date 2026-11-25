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

import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

/**
 * A Java class's bean accessors, as Kotlin and Jackson read them: KSP shows a Java field as a property and a getter as a
 * function, while a Kotlin caller reads `getX()` as the property `x` and Jackson writes it under `x` (#6839).
 */
internal object JavaBeans {
    /**
     * The public getter of [owner] that reads this Java [field]: the one Jackson names as the field, `getX()`, or
     * `isX()` for a boolean; null for a Kotlin property, or a field no public getter reads.
     */
    fun getterOf(field: KSPropertyDeclaration, owner: KSClassDeclaration): KSFunctionDeclaration? {
        if (field.origin !in JavaRecords.JAVA) return null
        val name = field.simpleName.asString()
        return owner.getAllFunctions().firstOrNull { it.beanProperty() == name }
    }

    /**
     * The public Java getters of [owner], its own or a Java base's, that read no field of their property's name, each
     * with that name, as Jackson takes a bean property: `getFullName()` is `fullName`, `isOpen()` of a boolean is `open`.
     */
    fun computed(owner: KSClassDeclaration, fields: Set<String>): List<Pair<String, KSFunctionDeclaration>> =
        owner.getAllFunctions().filter { it.origin in JavaRecords.JAVA }.mapNotNull { getter ->
            getter.beanProperty()?.takeUnless { it in fields }?.let { it to getter }
        }.toList()

    /**
     * The bean property this method reads, named as Jackson 3 names it, or null when Jackson takes it for no getter.
     * A getter is a public instance method of no parameter (`getAllFunctions` gives no static one): `getX()` returning a
     * value, or `isX()` returning a boolean, where `X` starts with an upper-case letter. Its property is `X` with that
     * letter in lower case (`getFullName()` is `fullName`), or `X` itself when its second letter is upper-case too
     * (`getURL()` is `URL`).
     */
    private fun KSFunctionDeclaration.beanProperty(): String? {
        val (prefix, bare) = GETTER.matchEntire(simpleName.asString())?.destructured ?: return null
        // A top-level class's name, which tells a boolean and a void apart.
        val returned = returnType!!.resolve().declaration
            .let { "${it.packageName.asString()}.${it.simpleName.asString()}" }
        val reads = if (prefix == "is") returned in BOOLEANS else returned !in VOIDS
        val getter = reads && isPublic() && parameters.isEmpty()
        val kept = bare.length > 1 && bare[1].isUpperCase()
        return (if (kept) bare else bare.first().lowercaseChar() + bare.substring(1)).takeIf { getter }
    }

    private val GETTER = Regex("(get|is)(\\p{Lu}.*)")
    private val BOOLEANS = setOf("kotlin.Boolean", "java.util.concurrent.atomic.AtomicBoolean")
    private val VOIDS = setOf("kotlin.Unit", "java.lang.Void")
}
