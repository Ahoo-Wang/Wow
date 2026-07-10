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
        return resolve(JsonSerializer.constructType(type))
    }

    fun resolve(type: JavaType): List<ResolvedJsonProperty> {
        return resolve(type, emptyMap())
    }

    fun resolve(type: ResolvedType): List<ResolvedJsonProperty> {
        val rootBindings = type.rawClass.kotlin.typeParameters.mapIndexedNotNull { index, parameter ->
            type.arguments.getOrNull(index)?.let { argument ->
                parameter to argument.toShape()
            }
        }.toMap()
        return resolve(type.javaType, rootBindings)
    }

    private fun resolve(
        type: JavaType,
        rootBindings: Map<KTypeParameter, KotlinTypeShape>,
    ): List<ResolvedJsonProperty> {
        return type.toBeanDescription()
            .findProperties()
            .asSequence()
            .filter(PropertyFilter::shouldInclude)
            .filter(BeanPropertyDefinition::couldSerialize)
            .map { property -> resolveProperty(type.rawClass, property, rootBindings) }
            .sortedBy(ResolvedJsonProperty::serializedName)
            .toList()
    }

    private fun resolveProperty(
        rootClass: Class<*>,
        property: BeanPropertyDefinition,
        rootBindings: Map<KTypeParameter, KotlinTypeShape>,
    ): ResolvedJsonProperty {
        val declaringMember = requireNotNull(property.accessor?.member) {
            "Unable to resolve serialization accessor for ${rootClass.name}.${property.name}"
        }
        val kotlinProperty = rootClass.kotlinProperty(declaringMember)
        if (kotlinProperty != null) {
            val typeParameterBindings = findTypeParameterBindings(
                rootClass = rootClass.kotlin,
                targetClass = declaringMember.declaringClass.kotlin,
                rootBindings = rootBindings,
            )
            val shape = kotlinProperty.returnType.toShape(typeParameterBindings)
            return ResolvedJsonProperty(
                serializedName = property.name,
                type = property.primaryType.resolveKotlinType(shape),
                origin = ResolvedTypeOrigin.KOTLIN,
                declaringMember = declaringMember,
            )
        }

        return ResolvedJsonProperty(
            serializedName = property.name,
            type = resolveJavaType(
                rootClass = rootClass,
                property = property,
                javaType = property.primaryType,
                evidence = property.javaAnnotationEvidence() +
                    declaringMember.inheritedKotlinEvidence(rootClass, rootBindings),
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
                    evidence = property.returnType.toShape(bindings).toEvidence(),
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
                parameter to argument.toShape(currentBindings)
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
    ): KotlinTypeShape {
        val typeParameter = classifier as? KTypeParameter
        if (typeParameter != null) {
            val substituted = substitutions[typeParameter]
            if (substituted != null) {
                return substituted.copy(
                    nullability = combineNullability(
                        substituted.nullability,
                        if (isMarkedNullable) Nullability.NULLABLE else Nullability.UNKNOWN,
                    )
                )
            }
            return KotlinTypeShape(
                nullability = if (isMarkedNullable) Nullability.NULLABLE else Nullability.UNKNOWN,
                arguments = emptyList(),
            )
        }
        val declaredArguments = arguments.map { projection ->
            projection.type?.toShape(substitutions)
        }
        return KotlinTypeShape(
            nullability = if (isMarkedNullable) Nullability.NULLABLE else Nullability.NON_NULL,
            arguments = semanticContainerArguments(declaredArguments) ?: declaredArguments,
        )
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

    private fun BeanPropertyDefinition.javaAnnotationEvidence(): List<JavaAnnotationEvidence> {
        return buildList {
            getter?.annotated?.let { method ->
                add(
                    method.annotatedReturnType.toEvidence(
                        declarationAnnotations = method.annotations.asIterable()
                    )
                )
            }
            field?.annotated?.let { field ->
                add(
                    field.annotatedType.toEvidence(
                        declarationAnnotations = field.annotations.asIterable()
                    )
                )
            }
            constructorParameters.forEachRemaining { parameter ->
                parameter.reflectionParameter()?.let { reflectionParameter ->
                    add(
                        reflectionParameter.annotatedType.toEvidence(
                            declarationAnnotations = reflectionParameter.annotations.asIterable()
                        )
                    )
                }
            }
        }
    }

    private fun AnnotatedParameter.reflectionParameter(): Parameter? {
        return (owner.member as? Executable)?.parameters?.getOrNull(index)
    }

    private fun AnnotatedType.toEvidence(
        declarationAnnotations: Iterable<Annotation> = emptyList(),
    ): JavaAnnotationEvidence {
        val signals = (declarationAnnotations + annotations.asIterable())
            .mapNotNull { it.toNullability() }
            .toSet()
        val arguments = when (this) {
            is AnnotatedParameterizedType -> annotatedActualTypeArguments.map { it.toEvidence() }
            is AnnotatedArrayType -> listOf(annotatedGenericComponentType.toEvidence())
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
