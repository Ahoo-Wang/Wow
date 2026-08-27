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

package me.ahoo.wow.compiler.query

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import me.ahoo.wow.compiler.AggregateRootResolver.resolveAggregateRootMetadata
import me.ahoo.wow.compiler.AggregateRootResolver.resolveDependencies
import me.ahoo.wow.compiler.GeneratedFile
import me.ahoo.wow.compiler.query.PropertyNav.Companion.NAV_DELIMITER
import me.ahoo.wow.compiler.query.PropertyNav.Companion.PROPERTY_DELIMITER
import me.ahoo.wow.naming.NamingConverter.Companion.pascalToSnake
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object StateAggregateRootResolver {
    const val GENERATOR_NAME = "me.ahoo.wow.compiler.query.QuerySymbolProcessorProvider"
    const val FILE_SUFFIX = "Properties"

    @Suppress("TooGenericExceptionCaught", "TooGenericExceptionThrown")
    fun KSClassDeclaration.resolveStateAggregateRoot(): GeneratedFile {
        val aggregateRootMetadata = this.resolveAggregateRootMetadata()
        val stateAggregateDeclaration = aggregateRootMetadata.state

        val packageName = stateAggregateDeclaration.packageName.asString()
        val fileName = stateAggregateDeclaration.simpleName.asString() + FILE_SUFFIX
        val codeGenerator = StringBuilder()
        codeGenerator.appendLine("package $packageName")
        codeGenerator.appendLine()
        codeGenerator.appendLine("import me.ahoo.wow.api.annotation.Generated")
        codeGenerator.appendLine()
        val generatedDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        codeGenerator.appendLine("@Generated(\"$GENERATOR_NAME\", date = \"$generatedDate\")")
        codeGenerator.appendLine("object $fileName {")
        val added = mutableSetOf<PropertyNav>()
        stateAggregateDeclaration.getAllProperties().forEach {
            it.resolvePropertyNavigationCode(codeGenerator, added)
        }
        codeGenerator.appendLine("}")
        val dependencies =
            Dependencies(aggregating = true, sources = aggregateRootMetadata.resolveDependencies().toTypedArray())
        return GeneratedFile(
            dependencies = dependencies,
            packageName = packageName,
            name = fileName,
            code = codeGenerator.toString()
        )
    }

    private fun String.toPropertyNav(parent: PropertyNav? = null): PropertyNav {
        if (parent == null) {
            return PropertyNav(this.pascalToSnake().uppercase(), this)
        }
        val propertyName = parent.property + PROPERTY_DELIMITER + this.pascalToSnake().uppercase()
        val nav = parent.nav + NAV_DELIMITER + this
        return PropertyNav(propertyName, nav)
    }

    private fun KSPropertyDeclaration.resolvePropertyNavigationCode(
        codeGenerator: StringBuilder,
        added: MutableSet<PropertyNav>,
        parent: PropertyNav? = null
    ) {
        val currentNav = this.simpleName.asString().toPropertyNav(parent)
        if (!added.add(currentNav)) {
            return
        }
        codeGenerator.appendLine(currentNav.toCode())
        val currentPropertyReturnTypeDeclaration = this.getter?.returnType?.resolve()?.declaration
        if (currentPropertyReturnTypeDeclaration is KSClassDeclaration &&
            currentPropertyReturnTypeDeclaration.shouldResolve(this)
        ) {
            currentPropertyReturnTypeDeclaration.getAllProperties().forEach {
                it.resolvePropertyNavigationCode(codeGenerator, added, currentNav)
            }
        }
    }

    private val SIMPLE_TYPE_MAPPING = setOf<String>()
    private fun KSClassDeclaration.shouldResolve(propertyDef: KSPropertyDeclaration): Boolean {
        if (this.classKind != ClassKind.CLASS) {
            return false
        }
        val typeName = checkNotNull(this.qualifiedName) {
            "[${propertyDef.parentDeclaration!!.qualifiedName!!.asString()}.$propertyDef] Unable to resolve qualifiedName for $this"
        }.asString()
        if (typeName.startsWith("kotlin.") || typeName.startsWith("java.")) {
            return false
        }
        return !SIMPLE_TYPE_MAPPING.contains(typeName)
    }
}

data class PropertyNav(val property: String, val nav: String) {
    fun toCode(): String {
        return "    const val $property = \"$nav\""
    }

    companion object {
        const val PROPERTY_DELIMITER = "__"
        const val NAV_DELIMITER = "."
    }
}
