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

package me.ahoo.wow.architecture

import me.ahoo.wow.architecture.ArchitectureRules.MODULES
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmValueParameter
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature

/**
 * The closure of the public API in Kotlin's own terms: the ABI dumps list the public members by their JVM signatures,
 * which erase a suspend function's return type (an `Object` beside a `Continuation`) and every generic argument
 * (`List`, `Flow`, `Map`), so a member can name a type no dump declares where [SurfaceRules.abiClosureViolations]
 * cannot see it (#7030). This rule reads the Kotlin metadata of each class a dump lists, from the compiled class
 * without loading it, and checks every type a listed member names: its parameters, receiver, return type, the
 * bounds of its type parameters and every argument of those types, as the class's supertypes.
 */
/** The compiled class of a class name in a module, or `null` when the module does not compile it. */
typealias ClassBytes = (WowProject, String) -> ByteArray?

object KotlinSignatureRules {
    /**
     * The `me/ahoo/wow` types a public member names in its Kotlin signature while no dump declares them, each with the
     * change that removes it. A fix removes its entry: an entry no member still names that way is stale.
     */
    val KOTLIN_SIGNATURE_ALLOWED: Map<String, String> = emptyMap()

    private val DECLARED_CLASS = Regex("""^(?:public|protected) [^{]*?\bclass ([\w/$]+)""")
    private val MEMBER = Regex("""^\s+(?:public|protected) .*?\b(fun|field) (\S+) (\S+)$""")
    private val SYNTHETIC = Regex("""^\s+(?:public|protected) (?:static )?synthetic """)
    private const val WOW = "me/ahoo/wow/"

    /** A class a dump lists and the signatures of its listed members (`name(descriptor)` or `name:descriptor`). */
    private class Listed(val dump: String, val module: WowProject, val name: String, val members: Set<String>)

    private fun listed(repositoryRoot: File): List<Listed> = MODULES.flatMap { module ->
        val path = "${module.dir}/api/${module.name}.api"
        val dump = File(repositoryRoot, path)
        if (!dump.isFile) return@flatMap emptyList()
        val classes = mutableListOf<Listed>()
        var owner: String? = null
        var members = mutableSetOf<String>()
        dump.readLines().forEach { line ->
            DECLARED_CLASS.find(line)?.let {
                owner = it.groupValues[1]
                members = mutableSetOf()
                return@forEach
            }
            if (line == "}") {
                owner?.let { classes += Listed(path, module, it, members) }
                owner = null
                return@forEach
            }
            if (owner == null || SYNTHETIC.containsMatchIn(line)) return@forEach
            MEMBER.find(line)?.let { match ->
                val (kind, name, descriptor) = match.destructured
                members += if (kind == "fun") "$name$descriptor" else "$name:$descriptor"
            }
        }
        classes
    }

    /** The compiled class of [name] in [module], from its Kotlin output; `null` for a class it does not compile. */
    fun compiledClass(repositoryRoot: File, module: WowProject, name: String): ByteArray? =
        File(repositoryRoot, "${module.dir}/build/classes/kotlin/main/$name.class").takeIf { it.isFile }?.readBytes()

    /**
     * The members of the classes the ABI dumps of [MODULES] list whose Kotlin signature names a `me/ahoo/wow` type no
     * dump declares, outside [allowed], as `dump: class.member: type`, and each allowed type no member names that way
     * any more (a stale entry). A listed class [classBytes] cannot find fails the rule, so that a missing build is no
     * silent pass; a class without Kotlin metadata (a Java class) has no erased Kotlin signature and is skipped.
     */
    fun closureViolations(
        repositoryRoot: File,
        allowed: Map<String, String> = KOTLIN_SIGNATURE_ALLOWED,
        classBytes: ClassBytes = { module, name -> compiledClass(repositoryRoot, module, name) },
    ): List<String> {
        val classes = listed(repositoryRoot)
        val declared = classes.map { it.name }.toSet()
        val leaked = mutableSetOf<String>()
        val violations = classes.flatMap { listed ->
            val bytes = classBytes(listed.module, listed.name)
                ?: return@flatMap listOf("${listed.dump}: ${listed.name}: no compiled class; build the module first")
            val named = metadataOf(bytes)?.let { namedTypes(it, listed.members) }.orEmpty()
            named.flatMap { (member, types) ->
                types.filter { it.startsWith(WOW) && it !in declared }.map { member to it }
            }.distinct().onEach { (_, type) -> leaked += type }
                .filter { (_, type) -> type !in allowed }
                .map { (member, type) -> "${listed.dump}: ${listed.name}.$member: $type" }
        }
        return violations + (allowed.keys - leaked).map { "stale entry: $it" }
    }

    /** The `@kotlin.Metadata` of a compiled class, read with ASM so that the class is never loaded. */
    fun metadataOf(bytes: ByteArray): KotlinClassMetadata? {
        val values = mutableMapOf<String, Any>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                    if (descriptor == "Lkotlin/Metadata;") MetadataVisitor(values) else null
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        if (values.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        val metadata = Metadata(
            kind = values["k"] as Int?,
            metadataVersion = values["mv"] as IntArray?,
            data1 = (values["d1"] as List<String>?)?.toTypedArray(),
            data2 = (values["d2"] as List<String>?)?.toTypedArray(),
            extraString = values["xs"] as String?,
            packageName = values["pn"] as String?,
            extraInt = values["xi"] as Int?,
        )
        return KotlinClassMetadata.readStrict(metadata)
    }

    private class MetadataVisitor(private val values: MutableMap<String, Any>) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String, value: Any) {
            values[name] = value
        }

        override fun visitArray(name: String): AnnotationVisitor {
            val items = mutableListOf<String>()
            values[name] = items
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any) {
                    items += value as String
                }
            }
        }
    }

    /** The types each listed member of [metadata] names, by the member's label; the class itself as `<class>`. */
    private fun namedTypes(metadata: KotlinClassMetadata, listed: Set<String>): Map<String, Set<String>> {
        val named = linkedMapOf<String, Set<String>>()
        val container: KmDeclarationContainer = when (metadata) {
            is KotlinClassMetadata.Class -> metadata.kmClass.also { named += classTypes(it, listed) }
            is KotlinClassMetadata.FileFacade -> metadata.kmPackage
            is KotlinClassMetadata.MultiFileClassPart -> metadata.kmPackage
            else -> return emptyMap()
        }
        container.functions.filter { it.signature?.toString() in listed }.forEach { named[it.name] = functionTypes(it) }
        container.properties.filter { property ->
            listOfNotNull(property.getterSignature, property.setterSignature).any { it.toString() in listed } ||
                property.fieldSignature?.toString() in listed
        }.forEach { named[it.name] = propertyTypes(it) }
        return named
    }

    /** A class's supertypes and type parameter bounds as `<class>`, and each listed constructor's parameters. */
    private fun classTypes(kmClass: KmClass, listed: Set<String>): Map<String, Set<String>> = buildMap {
        put("<class>", (kmClass.supertypes.flatMap(::typeNames) + kmClass.typeParameters.flatMap(::boundNames)).toSet())
        kmClass.constructors.filter { it.signature?.toString() in listed }.forEach { constructor ->
            put(constructor.signature.toString(), constructor.valueParameters.flatMap(::parameterNames).toSet())
        }
    }

    private fun functionTypes(function: KmFunction): Set<String> = buildSet {
        function.receiverParameterType?.let { addAll(typeNames(it)) }
        function.valueParameters.forEach { addAll(parameterNames(it)) }
        function.typeParameters.forEach { addAll(boundNames(it)) }
        addAll(typeNames(function.returnType))
    }

    private fun propertyTypes(property: KmProperty): Set<String> = buildSet {
        property.receiverParameterType?.let { addAll(typeNames(it)) }
        property.typeParameters.forEach { addAll(boundNames(it)) }
        addAll(typeNames(property.returnType))
    }

    private fun parameterNames(parameter: KmValueParameter): List<String> =
        typeNames(parameter.type) + parameter.varargElementType?.let(::typeNames).orEmpty()

    private fun boundNames(parameter: KmTypeParameter): List<String> = parameter.upperBounds.flatMap(::typeNames)

    /** The classes [type] names, its arguments' and its flexible upper bound's included, as JVM names. */
    private fun typeNames(type: KmType): List<String> = buildList {
        (type.classifier as? KmClassifier.Class)?.let { add(it.name.replace('.', '$')) }
        type.arguments.forEach { projection -> projection.type?.let { addAll(typeNames(it)) } }
        type.outerType?.let { addAll(typeNames(it)) }
        type.flexibleTypeUpperBound?.let { addAll(typeNames(it.type)) }
    }
}
