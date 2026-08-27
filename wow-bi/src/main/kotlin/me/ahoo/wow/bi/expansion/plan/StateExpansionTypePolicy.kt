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

package me.ahoo.wow.bi.expansion.plan

import me.ahoo.wow.bi.expansion.type.JacksonWireShapeInspector
import me.ahoo.wow.bi.expansion.type.JsonWireShape
import me.ahoo.wow.bi.expansion.type.Nullability
import me.ahoo.wow.bi.expansion.type.ResolvedType
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.bi.type.ClickHouseTypeMapping.scalarMapping
import me.ahoo.wow.bi.type.JsonTokenShape
import me.ahoo.wow.bi.type.ScalarMapping

internal fun ResolvedType.canRenderDirectly(): Boolean {
    if (verifiedScalarMapping() != null) {
        return true
    }
    if (javaType.isMapLikeType) {
        return JacksonWireShapeInspector.matches(this, JsonTokenShape.MAP) && hasSupportedMapShape()
    }
    if (javaType.isCollectionLikeType || javaType.isArrayType) {
        return JacksonWireShapeInspector.matches(this, JsonTokenShape.ARRAY) &&
            arguments.firstOrNull()?.verifiedScalarMapping() != null
    }
    return false
}

private fun ResolvedType.hasSupportedMapShape(): Boolean = supportedMapValueMapping() != null

internal fun ResolvedType.supportedMapValueMapping(): ScalarMapping? {
    val keyType = arguments.getOrNull(0)
    if (keyType?.rawClass != String::class.java ||
        keyType.nullability != Nullability.NON_NULL ||
        keyType.verifiedScalarMapping() == null
    ) {
        return null
    }
    return arguments.getOrNull(1)?.verifiedScalarMapping()
}

internal fun ResolvedType.verifiedScalarMapping(): ScalarMapping? {
    val mapping = rawClass.scalarMapping() ?: return null
    return mapping.takeIf { JacksonWireShapeInspector.matches(this, it.tokenShape) }
}

internal fun ResolvedType.toScalarType(
    mapping: ScalarMapping,
    nullableAncestor: Boolean,
): ClickHouseType {
    val scalar = mapping.clickHouseType
    return if (requiresRawCompanion() || nullableAncestor) {
        ClickHouseType.Nullable(scalar)
    } else {
        scalar
    }
}

internal fun ResolvedType.requiresRawCompanion(): Boolean = nullability != Nullability.NON_NULL

internal fun isUnsupportedPlatformObject(type: ResolvedType): Boolean {
    val packageName = type.rawClass.packageName
    return packageName.startsWith("java.") ||
        packageName.startsWith("javax.") ||
        packageName.startsWith("kotlin.")
}

internal fun ResolvedType.isOpaqueCollectionElement(): Boolean {
    return isUnsupportedPlatformObject(this) || JacksonWireShapeInspector.inspect(this) is JsonWireShape.Opaque
}
