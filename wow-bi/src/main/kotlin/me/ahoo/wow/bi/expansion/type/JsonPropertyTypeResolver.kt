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

package me.ahoo.wow.bi.expansion.type

import me.ahoo.wow.bi.expansion.plan.PropertyFilter
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toBeanDescription
import tools.jackson.databind.JavaType
import tools.jackson.databind.introspect.AnnotatedParameter
import tools.jackson.databind.introspect.BeanPropertyDefinition
import java.lang.reflect.AnnotatedArrayType
import java.lang.reflect.AnnotatedParameterizedType
import java.lang.reflect.AnnotatedType
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.RecordComponent
import java.lang.reflect.TypeVariable
import kotlin.Metadata
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

internal object JsonPropertyTypeResolver {
    fun resolve(type: Class<*>): List<ResolvedJsonProperty> {
        return resolve(JsonSerializer.constructType(type), emptyMap(), emptyMap())
    }

    fun resolve(type: JavaType): List<ResolvedJsonProperty> {
        return resolve(type, emptyMap(), emptyMap())
    }

    fun resolve(type: ResolvedType): List<ResolvedJsonProperty> {
        val kotlinRootBindings = type.rawClass.kotlin.typeParameters.mapIndexedNotNull { index, parameter ->
            type.arguments.getOrNull(index)?.let { argument ->
                parameter to argument.toShape()
            }
        }.toMap()
        val javaRootBindings = buildMap<TypeVariable<*>, JavaAnnotationEvidence> {
            type.rawClass.typeParameters.forEachIndexed { index, parameter ->
                type.arguments.getOrNull(index)?.let { argument ->
                    put(parameter, argument.toEvidence())
                }
            }
        }
        return resolve(type.javaType, kotlinRootBindings, javaRootBindings)
    }

    private fun resolve(
        type: JavaType,
        kotlinRootBindings: Map<KTypeParameter, KotlinTypeShape>,
        javaRootBindings: Map<TypeVariable<*>, JavaAnnotationEvidence>,
    ): List<ResolvedJsonProperty> {
        return type.toBeanDescription()
            .findProperties()
            .asSequence()
            .filter(PropertyFilter::shouldInclude)
            .filter(BeanPropertyDefinition::couldSerialize)
            .map { property ->
                resolveProperty(type.rawClass, property, kotlinRootBindings, javaRootBindings)
            }
            .sortedBy(ResolvedJsonProperty::serializedName)
            .toList()
    }

    private fun resolveProperty(
        rootClass: Class<*>,
        property: BeanPropertyDefinition,
        kotlinRootBindings: Map<KTypeParameter, KotlinTypeShape>,
        javaRootBindings: Map<TypeVariable<*>, JavaAnnotationEvidence>,
    ): ResolvedJsonProperty {
        val declaringMember = requireNotNull(property.accessor?.member) {
            "Unable to resolve serialization accessor for ${rootClass.name}.${property.name}"
        }
        val kotlinProperty = rootClass.kotlinProperty(declaringMember)
        if (kotlinProperty != null) {
            val typeParameterBindings = findTypeParameterBindings(
                rootClass = rootClass.kotlin,
                targetClass = declaringMember.declaringClass.kotlin,
                rootBindings = kotlinRootBindings,
            )
            val shape = kotlinProperty.returnType.toShape(
                substitutions = typeParameterBindings,
                declarationOrigin = ResolvedTypeOrigin.KOTLIN,
            )
            return ResolvedJsonProperty(
                serializedName = property.name,
                type = property.primaryType.resolveKotlinType(shape),
                origin = ResolvedTypeOrigin.KOTLIN,
                declaringMember = declaringMember,
            )
        }

        val javaTypeParameterBindingPaths = findJavaTypeParameterBindings(
            rootClass = rootClass,
            targetClass = declaringMember.declaringClass,
            rootBindings = javaRootBindings,
        )
        return ResolvedJsonProperty(
            serializedName = property.name,
            type = resolveJavaType(
                rootClass = rootClass,
                property = property,
                javaType = property.primaryType,
                evidence = javaTypeParameterBindingPaths.flatMap { bindings ->
                    property.javaAnnotationEvidence(bindings)
                } +
                    declaringMember.inheritedKotlinEvidence(rootClass, kotlinRootBindings),
                path = property.name,
            ),
            origin = ResolvedTypeOrigin.JAVA,
            declaringMember = declaringMember,
        )
    }

    private fun Class<*>.kotlinProperty(member: Member): KProperty1<*, *>? {
        if (getDeclaredAnnotation(Metadata::class.java) == null) {
            return null
        }
        return kotlin.memberProperties.firstOrNull { property ->
            when (member) {
                is Method -> property.javaGetter == member
                is Field -> property.javaField == member
                else -> false
            }
        }
    }

    private fun Member.inheritedKotlinEvidence(
        rootClass: Class<*>,
        rootBindings: Map<KTypeParameter, KotlinTypeShape>,
    ): List<JavaAnnotationEvidence> {
        val method = this as? Method ?: return emptyList()
        val contracts = rootClass.allSupertypes()
            .mapNotNull { supertype ->
                val overriddenMethod = supertype.declaredMethods.firstOrNull { candidate ->
                    candidate.name == method.name &&
                        candidate.parameterTypes.contentEquals(method.parameterTypes) &&
                        !candidate.isBridge
                } ?: return@mapNotNull null
                val property = supertype.kotlinProperty(overriddenMethod) ?: return@mapNotNull null
                val bindings = findTypeParameterBindings(
                    rootClass = rootClass.kotlin,
                    targetClass = supertype.kotlin,
                    rootBindings = rootBindings,
                )
                InheritedKotlinContract(
                    declaringClass = supertype,
                    evidence = property.returnType.toShape(
                        substitutions = bindings,
                        declarationOrigin = ResolvedTypeOrigin.KOTLIN,
                    ).toEvidence(),
                )
            }
        return contracts.filter { candidate ->
            contracts.none { other ->
                other !== candidate &&
                    candidate.declaringClass.isAssignableFrom(other.declaringClass)
            }
        }.map(InheritedKotlinContract::evidence)
    }

    private fun Class<*>.allSupertypes(): List<Class<*>> {
        val discovered = linkedSetOf<Class<*>>()

        fun visit(type: Class<*>) {
            type.interfaces.sortedBy(Class<*>::getName).forEach { supertype ->
                if (discovered.add(supertype)) {
                    visit(supertype)
                }
            }
            type.superclass?.takeIf { it != Any::class.java }?.let { supertype ->
                if (discovered.add(supertype)) {
                    visit(supertype)
                }
            }
        }

        visit(this)
        return discovered.toList()
    }

    private fun findJavaTypeParameterBindings(
        rootClass: Class<*>,
        targetClass: Class<*>,
        rootBindings: Map<TypeVariable<*>, JavaAnnotationEvidence>,
    ): List<Map<TypeVariable<*>, JavaAnnotationEvidence>> {
        return findJavaTypeParameterBindings(
            currentClass = rootClass,
            currentBindings = rootBindings,
            targetClass = targetClass,
            visited = emptySet(),
        ).ifEmpty { listOf(emptyMap()) }
    }

    private fun findJavaTypeParameterBindings(
        currentClass: Class<*>,
        currentBindings: Map<TypeVariable<*>, JavaAnnotationEvidence>,
        targetClass: Class<*>,
        visited: Set<Class<*>>,
    ): List<Map<TypeVariable<*>, JavaAnnotationEvidence>> {
        if (currentClass == targetClass) {
            return listOf(currentBindings)
        }
        if (currentClass in visited) {
            return emptyList()
        }
        val currentPath = visited + currentClass

        val supertypes = buildList {
            addAll(currentClass.annotatedInterfaces)
            currentClass.annotatedSuperclass?.let { superclass -> add(superclass) }
        }
            .mapNotNull { annotatedType ->
                annotatedType.rawClass()?.let { rawClass -> rawClass to annotatedType }
            }
            .filter { (rawClass) -> targetClass.isAssignableFrom(rawClass) }
            .sortedBy { (rawClass) -> rawClass.name }
        return supertypes.flatMap { (superClass, annotatedType) ->
            val annotatedArguments = (annotatedType as? AnnotatedParameterizedType)
                ?.annotatedActualTypeArguments
                .orEmpty()
            val superBindings = buildMap<TypeVariable<*>, JavaAnnotationEvidence> {
                superClass.typeParameters.forEachIndexed { index, parameter ->
                    annotatedArguments.getOrNull(index)?.let { argument ->
                        put(parameter, argument.toEvidence(substitutions = currentBindings))
                    }
                }
            }
            findJavaTypeParameterBindings(
                currentClass = superClass,
                currentBindings = superBindings,
                targetClass = targetClass,
                visited = currentPath,
            )
        }
    }

    private fun AnnotatedType.rawClass(): Class<*>? {
        return when (val reflectedType = type) {
            is Class<*> -> reflectedType
            is ParameterizedType -> reflectedType.rawType as? Class<*>
            else -> null
        }
    }

    private fun KClass<*>.declarationOrigin(): ResolvedTypeOrigin {
        return if (java.getDeclaredAnnotation(Metadata::class.java) == null) {
            ResolvedTypeOrigin.JAVA
        } else {
            ResolvedTypeOrigin.KOTLIN
        }
    }

    private fun findTypeParameterBindings(
        rootClass: KClass<*>,
        targetClass: KClass<*>,
        rootBindings: Map<KTypeParameter, KotlinTypeShape>,
    ): Map<KTypeParameter, KotlinTypeShape> {
        return findTypeParameterBindings(
            currentClass = rootClass,
            currentBindings = rootBindings,
            targetClass = targetClass,
            visited = mutableSetOf(),
        ).orEmpty()
    }

    private fun findTypeParameterBindings(
        currentClass: KClass<*>,
        currentBindings: Map<KTypeParameter, KotlinTypeShape>,
        targetClass: KClass<*>,
        visited: MutableSet<KClass<*>>,
    ): Map<KTypeParameter, KotlinTypeShape>? {
        if (currentClass == targetClass) {
            return currentBindings
        }
        if (!visited.add(currentClass)) {
            return null
        }

        currentClass.supertypes.forEach { supertype ->
            val superClass = supertype.classifier as? KClass<*> ?: return@forEach
            val superBindings = superClass.typeParameters.mapIndexedNotNull { index, parameter ->
                val argument = supertype.arguments.getOrNull(index)?.type ?: return@mapIndexedNotNull null
                parameter to argument.toShape(
                    substitutions = currentBindings,
                    declarationOrigin = currentClass.declarationOrigin(),
                )
            }.toMap()
            findTypeParameterBindings(
                currentClass = superClass,
                currentBindings = superBindings,
                targetClass = targetClass,
                visited = visited,
            )?.let { return it }
        }
        return null
    }

    private fun KType.toShape(
        substitutions: Map<KTypeParameter, KotlinTypeShape>,
        declarationOrigin: ResolvedTypeOrigin,
    ): KotlinTypeShape {
        val typeParameter = classifier as? KTypeParameter
        if (typeParameter != null) {
            val useSiteNullability = explicitNullability()
            val substituted = substitutions[typeParameter]
            if (substituted != null) {
                return substituted.copy(
                    nullability = combineNullability(
                        substituted.nullability,
                        useSiteNullability,
                    )
                )
            }
            return KotlinTypeShape(
                nullability = useSiteNullability,
                arguments = emptyList(),
            )
        }
        val declaredArguments = arguments.map { projection ->
            projection.type?.toShape(substitutions, declarationOrigin)
        }
        return KotlinTypeShape(
            nullability = concreteNullability(declarationOrigin),
            arguments = semanticContainerArguments(declaredArguments) ?: declaredArguments,
        )
    }

    private fun KType.concreteNullability(declarationOrigin: ResolvedTypeOrigin): Nullability {
        val explicitNullability = explicitNullability()
        if (explicitNullability != Nullability.UNKNOWN) {
            return explicitNullability
        }
        val rawClass = classifier as? KClass<*>
        return if (rawClass?.java?.isPrimitive == true || declarationOrigin == ResolvedTypeOrigin.KOTLIN) {
            Nullability.NON_NULL
        } else {
            Nullability.UNKNOWN
        }
    }

    private fun KType.explicitNullability(): Nullability {
        val signals = annotations.mapNotNull { it.toNullability() }.toMutableSet()
        if (isMarkedNullable) {
            signals.add(Nullability.NULLABLE)
        }
        require(!(signals.contains(Nullability.NULLABLE) && signals.contains(Nullability.NON_NULL))) {
            "Conflicting nullability annotations on generic type [$this]."
        }
        return when {
            signals.contains(Nullability.NULLABLE) -> Nullability.NULLABLE
            signals.contains(Nullability.NON_NULL) -> Nullability.NON_NULL
            else -> Nullability.UNKNOWN
        }
    }

    private fun KType.semanticContainerArguments(
        declaredArguments: List<KotlinTypeShape?>,
    ): List<KotlinTypeShape?>? {
        val rawClass = classifier as? KClass<*> ?: return null
        val semanticClass = when {
            Map::class.java.isAssignableFrom(rawClass.java) -> Map::class
            Collection::class.java.isAssignableFrom(rawClass.java) -> Collection::class
            else -> return null
        }
        val declaredBindings = rawClass.typeParameters.mapIndexedNotNull { index, parameter ->
            declaredArguments.getOrNull(index)?.let { argument -> parameter to argument }
        }.toMap()
        val semanticBindings = findTypeParameterBindings(
            rootClass = rawClass,
            targetClass = semanticClass,
            rootBindings = declaredBindings,
        )
        return semanticClass.typeParameters.map(semanticBindings::get)
    }

    private fun ResolvedType.toShape(): KotlinTypeShape {
        return KotlinTypeShape(
            nullability = nullability,
            arguments = arguments.map { argument -> argument.toShape() },
        )
    }

    private fun KotlinTypeShape.toEvidence(): JavaAnnotationEvidence {
        return JavaAnnotationEvidence(
            signals = setOf(nullability),
            arguments = arguments.map { argument ->
                argument?.toEvidence() ?: JavaAnnotationEvidence(emptySet(), emptyList())
            },
        )
    }

    private fun ResolvedType.toEvidence(): JavaAnnotationEvidence {
        return JavaAnnotationEvidence(
            signals = setOf(nullability),
            arguments = arguments.map { argument -> argument.toEvidence() },
        )
    }

    private fun JavaType.resolveKotlinType(shape: KotlinTypeShape?): ResolvedType {
        val argumentTypes = argumentTypes()
        return ResolvedType(
            javaType = this,
            nullability = if (isPrimitive) Nullability.NON_NULL else shape?.nullability ?: Nullability.UNKNOWN,
            arguments = argumentTypes.mapIndexed { index, argumentType ->
                argumentType.resolveKotlinType(shape?.arguments?.getOrNull(index))
            },
        )
    }

    private fun resolveJavaType(
        rootClass: Class<*>,
        property: BeanPropertyDefinition,
        javaType: JavaType,
        evidence: List<JavaAnnotationEvidence>,
        path: String,
    ): ResolvedType {
        val signals = evidence.flatMapTo(mutableSetOf()) { it.signals }
        if (javaType.isPrimitive) {
            signals.add(Nullability.NON_NULL)
        }
        require(!(signals.contains(Nullability.NULLABLE) && signals.contains(Nullability.NON_NULL))) {
            "Conflicting nullability annotations for ${rootClass.name}.${property.name} at $path"
        }
        val nullability = when {
            signals.contains(Nullability.NULLABLE) -> Nullability.NULLABLE
            signals.contains(Nullability.NON_NULL) -> Nullability.NON_NULL
            else -> Nullability.UNKNOWN
        }
        return ResolvedType(
            javaType = javaType,
            nullability = nullability,
            arguments = javaType.argumentTypes().mapIndexed { index, argumentType ->
                resolveJavaType(
                    rootClass = rootClass,
                    property = property,
                    javaType = argumentType,
                    evidence = evidence.mapNotNull { it.arguments.getOrNull(index) },
                    path = "$path[$index]",
                )
            },
        )
    }

    private fun BeanPropertyDefinition.javaAnnotationEvidence(
        substitutions: Map<TypeVariable<*>, JavaAnnotationEvidence>,
    ): List<JavaAnnotationEvidence> {
        return buildList {
            getter?.annotated?.let { method ->
                add(
                    method.annotatedReturnType.toEvidence(
                        declarationAnnotations = method.annotations.asIterable(),
                        substitutions = substitutions,
                    )
                )
            }
            field?.annotated?.let { field ->
                add(
                    field.annotatedType.toEvidence(
                        declarationAnnotations = field.annotations.asIterable(),
                        substitutions = substitutions,
                    )
                )
            }
            constructorParameters.forEachRemaining { parameter ->
                parameter.reflectionParameter()?.let { reflectionParameter ->
                    add(
                        reflectionParameter.annotatedType.toEvidence(
                            declarationAnnotations = reflectionParameter.annotations.asIterable(),
                            substitutions = substitutions,
                        )
                    )
                }
            }
            recordComponent()?.let { component ->
                add(
                    component.annotatedType.toEvidence(
                        declarationAnnotations = component.annotations.asIterable(),
                        substitutions = substitutions,
                    )
                )
            }
        }
    }

    private fun BeanPropertyDefinition.recordComponent(): RecordComponent? {
        val member = accessor?.member ?: return null
        val declaringClass = member.declaringClass
        if (!declaringClass.isRecord) {
            return null
        }
        return declaringClass.recordComponents.firstOrNull { component ->
            when (member) {
                is Method -> component.accessor == member
                is Field -> component.name == member.name
                else -> false
            }
        }
    }

    private fun AnnotatedParameter.reflectionParameter(): Parameter? {
        return (owner.member as? Executable)?.parameters?.getOrNull(index)
    }

    private fun AnnotatedType.toEvidence(
        declarationAnnotations: Iterable<Annotation> = emptyList(),
        substitutions: Map<TypeVariable<*>, JavaAnnotationEvidence> = emptyMap(),
    ): JavaAnnotationEvidence {
        val signals = (declarationAnnotations + annotations.asIterable())
            .mapNotNull { it.toNullability() }
            .toSet()
        val substituted = (type as? TypeVariable<*>)?.let(substitutions::get)
        if (substituted != null) {
            return JavaAnnotationEvidence(
                signals = signals.ifEmpty { substituted.signals },
                arguments = substituted.arguments,
            )
        }
        val arguments = when (this) {
            is AnnotatedParameterizedType -> annotatedActualTypeArguments.map {
                it.toEvidence(substitutions = substitutions)
            }
            is AnnotatedArrayType -> listOf(
                annotatedGenericComponentType.toEvidence(substitutions = substitutions)
            )
            else -> emptyList()
        }
        return JavaAnnotationEvidence(signals = signals, arguments = arguments)
    }

    private fun Annotation.toNullability(): Nullability? {
        return when (annotationClass.java.simpleName) {
            "Nullable", "CheckForNull" -> Nullability.NULLABLE
            "NotNull", "NonNull" -> Nullability.NON_NULL
            else -> null
        }
    }

    private fun JavaType.argumentTypes(): List<JavaType> {
        if (isArrayType) {
            return listOf(contentType)
        }
        if (isMapLikeType) {
            return listOfNotNull(keyType, contentType)
        }
        if (isCollectionLikeType) {
            return listOfNotNull(contentType)
        }
        return (0 until containedTypeCount()).mapNotNull(::containedType)
    }

    private fun combineNullability(
        first: Nullability,
        second: Nullability,
    ): Nullability {
        return when {
            first == Nullability.NULLABLE || second == Nullability.NULLABLE -> Nullability.NULLABLE
            first == Nullability.NON_NULL || second == Nullability.NON_NULL -> Nullability.NON_NULL
            else -> Nullability.UNKNOWN
        }
    }

    private data class KotlinTypeShape(
        val nullability: Nullability,
        val arguments: List<KotlinTypeShape?>,
    )

    private data class JavaAnnotationEvidence(
        val signals: Set<Nullability>,
        val arguments: List<JavaAnnotationEvidence>,
    )

    private data class InheritedKotlinContract(
        val declaringClass: Class<*>,
        val evidence: JavaAnnotationEvidence,
    )
}
