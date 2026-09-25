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
import com.google.devtools.ksp.symbol.KSAnnotation
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
    private const val JSON_PROPERTY_NAME = "com.fasterxml.jackson.annotation.JsonProperty"
    private const val JSON_IGNORE_NAME = "com.fasterxml.jackson.annotation.JsonIgnore"
    private const val JSON_IGNORE_PROPERTIES_NAME = "com.fasterxml.jackson.annotation.JsonIgnoreProperties"
    private val JACKSON_PROPERTY_ANNOTATIONS = setOf(JSON_PROPERTY_NAME, JSON_IGNORE_NAME)

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
            it.resolvePropertyNavigationCode(stateAggregateDeclaration, codeGenerator, added)
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

    private fun KSPropertyDeclaration.toPropertyNav(owner: KSClassDeclaration, parent: PropertyNav?): PropertyNav {
        val kotlinName = this.simpleName.asString()
        val jackson = this.jacksonAnnotations()
        val wireName = jackson.firstNotNullOfOrNull { it.stringArgument(JSON_PROPERTY_NAME) }
            ?.takeIf { it.isNotEmpty() } ?: kotlinName
        val ignored = parent?.ignored == true ||
            jackson.any { it.isJsonIgnore() } ||
            owner.ignoredPropertyNames().let { kotlinName in it || wireName in it }
        val constantName = kotlinName.pascalToSnake().uppercase()
        if (parent == null) {
            return PropertyNav(constantName, wireName, ignored)
        }
        return PropertyNav(
            property = parent.property + PROPERTY_DELIMITER + constantName,
            nav = parent.nav + NAV_DELIMITER + wireName,
            ignored = ignored
        )
    }

    /**
     * Jackson annotations placed on the property, its getter, its backing field
     * or its primary-constructor parameter (`@param:`).
     */
    private fun KSPropertyDeclaration.jacksonAnnotations(): List<KSAnnotation> {
        val name = this.simpleName.asString()
        val constructorParameterAnnotations = (this.parentDeclaration as? KSClassDeclaration)
            ?.primaryConstructor
            ?.parameters
            ?.firstOrNull { it.name?.asString() == name }
            ?.annotations
            .orEmpty()
        return (annotations + getter?.annotations.orEmpty() + constructorParameterAnnotations)
            .filter { it.qualifiedName() in JACKSON_PROPERTY_ANNOTATIONS }
            .toList()
    }

    private fun KSAnnotation.qualifiedName(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    private fun KSAnnotation.argument(name: String): Any? =
        arguments.firstOrNull { it.name?.asString() == name }?.value

    private fun KSAnnotation.stringArgument(annotationName: String): String? {
        if (qualifiedName() != annotationName) {
            return null
        }
        return argument("value") as? String
    }

    private fun KSAnnotation.isJsonIgnore(): Boolean =
        qualifiedName() == JSON_IGNORE_NAME && argument("value") as? Boolean ?: true

    private fun KSClassDeclaration.ignoredPropertyNames(): Set<String> =
        annotations
            .filter { it.qualifiedName() == JSON_IGNORE_PROPERTIES_NAME }
            .flatMap { (it.argument("value") as? List<*>).orEmpty() }
            .filterIsInstance<String>()
            .toSet()

    private fun KSPropertyDeclaration.resolvePropertyNavigationCode(
        owner: KSClassDeclaration,
        codeGenerator: StringBuilder,
        added: MutableSet<PropertyNav>,
        parent: PropertyNav? = null
    ) {
        val currentNav = this.toPropertyNav(owner, parent)
        if (!added.add(currentNav)) {
            return
        }
        codeGenerator.appendLine(currentNav.toCode())
        val currentPropertyReturnTypeDeclaration = this.getter?.returnType?.resolve()?.declaration
        if (currentPropertyReturnTypeDeclaration is KSClassDeclaration &&
            currentPropertyReturnTypeDeclaration.shouldResolve(this)
        ) {
            currentPropertyReturnTypeDeclaration.getAllProperties().forEach {
                it.resolvePropertyNavigationCode(currentPropertyReturnTypeDeclaration, codeGenerator, added, currentNav)
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

/**
 * One generated constant: [property] is the constant name (derived from Kotlin property names),
 * [nav] the constant value (the Jackson serialized path).
 * An [ignored] path is not serialized, so its constant is kept for source compatibility but deprecated.
 */
data class PropertyNav(val property: String, val nav: String, val ignored: Boolean = false) {
    fun toCode(): String {
        val constant = "    const val $property = \"$nav\""
        if (!ignored) {
            return constant
        }
        return "    @Deprecated(\"$nav is not serialized (@JsonIgnore); queries on it are rejected.\")\n$constant"
    }

    companion object {
        const val PROPERTY_DELIMITER = "__"
        const val NAV_DELIMITER = "."
    }
}
