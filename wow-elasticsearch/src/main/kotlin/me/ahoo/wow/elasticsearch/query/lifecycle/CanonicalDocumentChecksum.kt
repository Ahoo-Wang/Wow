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

package me.ahoo.wow.elasticsearch.query.lifecycle

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.LinkedHashMap

internal fun canonicalDocumentHash(
    source: Map<*, *>,
    limits: SnapshotCanonicalChecksumLimits,
    excludedRootKeys: Set<String> = emptySet(),
): ByteArray {
    val canonical = CanonicalDocumentSnapshotter(limits).snapshotRoot(source, excludedRootKeys)
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { output -> output.writeCanonical(canonical) }
    return newLifecycleSha256().digest(buffer.toByteArray())
}

private class CanonicalDocumentSnapshotter(private val limits: SnapshotCanonicalChecksumLimits) {
    private val active = IdentityHashMap<Any, Unit>()
    private var nodes = 0
    private var payloadBytes = 0L

    fun snapshotRoot(
        source: Map<*, *>,
        excludedRootKeys: Set<String>,
    ): CanonicalValue.ObjectValue = snapshotObject(source, 0, excludedRootKeys)

    private fun snapshot(value: Any?, depth: Int): CanonicalValue = when (value) {
        null -> enterNode(depth) { CanonicalValue.Null }
        is Boolean -> enterNode(depth) { CanonicalValue.BooleanValue(value) }
        is String -> enterNode(depth) { CanonicalValue.Text(snapshotString(value)) }
        is Number -> enterNode(depth) { value.toCanonicalNumber() }
        is ByteArray -> enterNode(depth) {
            consumePayload(value.size.toLong())
            CanonicalValue.Bytes(value.copyOf())
        }

        is Map<*, *> -> snapshotObject(value, depth, emptySet())
        is Iterable<*> -> snapshotIterable(value, depth)
        is Array<*> -> snapshotIterable(value.asIterable(), depth)
        else -> throw IllegalArgumentException(
            "Canonical index verification does not support value type ${value::class.qualifiedName}.",
        )
    }

    private fun snapshotObject(
        source: Map<*, *>,
        depth: Int,
        excludedKeys: Set<String>,
    ): CanonicalValue.ObjectValue = withContainer(source, depth) {
        val values = LinkedHashMap<String, CanonicalValue>()
        val iterator = source.entries.iterator()
        var count = 0
        while (iterator.hasNext()) {
            require(++count <= limits.maxCollectionSize) {
                "Canonical index verification object exceeds its field limit."
            }
            val entry = iterator.next()
            val key = entry.key as? String
                ?: throw IllegalArgumentException("Canonical index verification object keys must be strings.")
            if (key in excludedKeys) continue
            require(!values.containsKey(key)) { "Canonical index verification object contains a duplicate key." }
            snapshotString(key)
            values[key] = snapshot(entry.value, depth + 1)
        }
        CanonicalValue.ObjectValue(values.toSortedMap())
    }

    private fun snapshotIterable(source: Iterable<*>, depth: Int): CanonicalValue.ListValue =
        withContainer(source, depth) {
            val values = ArrayList<CanonicalValue>()
            val iterator = source.iterator()
            while (iterator.hasNext()) {
                require(values.size < limits.maxCollectionSize) {
                    "Canonical index verification list exceeds its item limit."
                }
                values += snapshot(iterator.next(), depth + 1)
            }
            CanonicalValue.ListValue(values)
        }

    private fun snapshotString(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limits.maxStringBytes) {
            "Canonical index verification string exceeds its byte limit."
        }
        consumePayload(bytes.size.toLong())
        return value
    }

    private fun Number.toCanonicalNumber(): CanonicalValue.NumberValue {
        val decimal = when (this) {
            is BigDecimal -> this
            is BigInteger -> BigDecimal(this)
            is Byte, is Short, is Int, is Long -> BigDecimal.valueOf(toLong())
            is Float, is Double -> {
                val value = toDouble()
                require(value.isFinite()) { "Canonical index verification numbers must be finite." }
                BigDecimal.valueOf(value)
            }

            else -> try {
                BigDecimal(toString())
            } catch (error: NumberFormatException) {
                throw IllegalArgumentException("Canonical index verification number is not canonicalizable.", error)
            }
        }.canonical()
        val unscaled = decimal.unscaledValue().toString()
        consumePayload(unscaled.toByteArray(Charsets.UTF_8).size.toLong())
        return CanonicalValue.NumberValue(unscaled, decimal.scale())
    }

    private fun consumePayload(bytes: Long) {
        payloadBytes = Math.addExact(payloadBytes, bytes)
        require(payloadBytes <= limits.maxPayloadBytesPerDocument) {
            "Canonical index verification document exceeds its payload limit."
        }
    }

    private inline fun <T> enterNode(depth: Int, block: () -> T): T {
        require(depth <= limits.maxDepth && ++nodes <= limits.maxNodesPerDocument) {
            "Canonical index verification document exceeds its structural limits."
        }
        return block()
    }

    private inline fun <T> withContainer(source: Any, depth: Int, block: () -> T): T =
        enterNode(depth) {
            require(active.put(source, Unit) == null) {
                "Canonical index verification document contains a container cycle."
            }
            try {
                block()
            } finally {
                active.remove(source)
            }
        }
}

private sealed interface CanonicalValue {
    data object Null : CanonicalValue
    data class BooleanValue(val value: Boolean) : CanonicalValue
    data class Text(val value: String) : CanonicalValue
    data class NumberValue(val unscaled: String, val scale: Int) : CanonicalValue
    data class Bytes(val value: ByteArray) : CanonicalValue
    data class ListValue(val values: List<CanonicalValue>) : CanonicalValue
    data class ObjectValue(val values: Map<String, CanonicalValue>) : CanonicalValue
}

private fun DataOutputStream.writeCanonical(value: CanonicalValue) {
    when (value) {
        CanonicalValue.Null -> writeByte(0)
        is CanonicalValue.BooleanValue -> {
            writeByte(1)
            writeBoolean(value.value)
        }

        is CanonicalValue.Text -> {
            writeByte(2)
            writeUtf8(value.value)
        }

        is CanonicalValue.NumberValue -> {
            writeByte(3)
            writeUtf8(value.unscaled)
            writeInt(value.scale)
        }

        is CanonicalValue.Bytes -> {
            writeByte(4)
            writeInt(value.value.size)
            write(value.value)
        }

        is CanonicalValue.ListValue -> {
            writeByte(5)
            writeInt(value.values.size)
            value.values.forEach { nested -> writeCanonical(nested) }
        }

        is CanonicalValue.ObjectValue -> {
            writeByte(6)
            writeInt(value.values.size)
            value.values.forEach { (key, nested) ->
                writeUtf8(key)
                writeCanonical(nested)
            }
        }
    }
}

private fun DataOutputStream.writeUtf8(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun BigDecimal.canonical(): BigDecimal =
    if (compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else stripTrailingZeros()

internal fun newLifecycleSha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

internal fun MessageDigest.updateLifecycleUtf8(value: String) =
    updateLifecycleLengthPrefixed(value.toByteArray(Charsets.UTF_8))

internal fun MessageDigest.updateLifecycleLengthPrefixed(bytes: ByteArray) {
    update(
        byteArrayOf(
            (bytes.size ushr 24).toByte(),
            (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(),
            bytes.size.toByte(),
        ),
    )
    update(bytes)
}

internal fun ByteArray.toLowerHex(): String = joinToString("") { byte -> "%02x".format(byte) }
