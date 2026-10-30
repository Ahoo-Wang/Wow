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

package me.ahoo.wow.manifest

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** [ManifestSchema] describes what [ManifestTree] writes, both ways: every key written is declared, and written. */
class ManifestSchemaTest {
    private val mapper = jacksonObjectMapper()

    /** A Manifest with every optional value of the format set, so its tree has every key the schema declares. */
    private val full: Manifest = Manifests.fragment().let { fragment ->
        val context = fragment.contexts.single()
        val subscriptions = context.subscriptions.mapIndexed { index, subscription ->
            if (index != 0) {
                subscription
            } else {
                subscription.copyWith(
                    source = subscription.source.copyWith(
                        events = listOf("CartCreated"),
                        eventSchemas = listOf("#/\$defs/x")
                    ),
                    committed = listOf("shop.cart.PutItem"),
                    calls = listOf("shop.cart.PutItem"),
                    schedules = listOf("shop.cart.PutItem"),
                    derives = emptyList(),
                    rejected = emptyList(),
                    retry = RetryManifest(3, "PT1S", "PT10S", "PT30S", listOf("a"), listOf("b"), 2.0, listOf("c")),
                    job = JobManifest("daily", "0 0 9 * * *", "UTC", "sharded", "catch-up"),
                )
            }
        }
        val aggregates = context.aggregates.map { aggregate ->
            aggregate.copyWith(
                state = aggregate.state.copyWith(view = "sample.CartStateView"),
                snapshot = SnapshotManifest(true, 2),
                schedules = listOf("shop.cart.PutItem"),
                ownership = Ownership.TENANT,
                retired = listOf("CartDropped"),
                idSource = AggregateIdSource.OWNER,
                commands = aggregate.commands.mapIndexed { index, command ->
                    if (index == 0) {
                        command.copyWith(mounted = true, transformsTo = listOf("shop.cart.PutItem"), description = "d")
                    } else {
                        command
                    }
                },
                events = aggregate.events.map { it.copyWith(description = "d") },
            )
        }
        fragment.copyWith(
            service = ServiceInfo("shop-service", "1.0.0"),
            errors = FrameworkErrors.ALL,
            requires = listOf("x"),
            contexts = listOf(
                context.copyWith(
                    aggregates = aggregates,
                    subscriptions = subscriptions,
                    commands = SystemCommands.jobs(context.name),
                    routes = listOf(
                        ServiceRouteManifest(
                            name = "summary",
                            route = RouteManifest("GET", "/shop/summary/{day}"),
                            parameters = listOf(
                                RouteParameterManifest(
                                    "day",
                                    RouteParameterManifest.Location.PATH,
                                    mapOf("type" to "string")
                                ),
                                RouteParameterManifest(
                                    "status",
                                    RouteParameterManifest.Location.QUERY,
                                    mapOf("type" to "string"),
                                    required = false,
                                ),
                            ),
                            request = Manifest.arraySchema(Manifest.refSchema("x"), unique = true),
                            response = Manifest.refSchema("x"),
                            errors = listOf("shop.summary.closed"),
                            description = "The day's summary.",
                            deprecated = true,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `what ManifestTree writes should be valid under the schema`() {
        val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val schema = registry.getSchema(mapper.writeValueAsString(ManifestSchema.schema), InputFormat.JSON)
        listOf(full, Manifests.fragment(), Manifest()).forEach { manifest ->
            schema.validate(mapper.writeValueAsString(ManifestTree.write(manifest)), InputFormat.JSON)
                .map { it.toString() }.assert().isEmpty()
        }
        // A required key left out is not valid.
        schema.validate("""{"contexts":[]}""", InputFormat.JSON).assert().isNotEmpty()
    }

    @Test
    fun `the published copy of the schema should be the schema`() {
        val actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ManifestSchema.schema) + "\n"
        val copy = Path.of("src/main/resources/${ManifestSchema.RESOURCE}")
        if (System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true)) {
            copy.parent.createDirectories()
            copy.writeText(actual)
        }
        copy.exists().assert().describedAs("Run with -Dwow.snapshot.update=true to write $copy.").isTrue()
        copy.readText().assert().describedAs("Run with -Dwow.snapshot.update=true to write $copy.").isEqualTo(actual)
        javaClass.classLoader.getResource(ManifestSchema.RESOURCE).assert().isNotNull()
    }

    @Test
    fun `every key ManifestTree writes should be declared, and every key declared written`() {
        val declared = sortedSetOf<String>()
        val written = sortedSetOf<String>()
        walk(ManifestSchema.schema, ManifestTree.write(full), "Manifest", declared, written)
        written.assert().isEqualTo(declared)
    }

    /**
     * The keys [schema] declares, and those [node] has, of every object under [path], resolving references: an object
     * of a definition is named by it, so the keys of a command count wherever a command is written.
     */
    private fun walk(
        schema: Map<*, *>,
        node: Any?,
        at: String,
        declared: MutableSet<String>,
        written: MutableSet<String>
    ) {
        val resolved = resolve(schema)
        val path = (schema["\$ref"] as? String)?.removePrefix("#/\$defs/") ?: at
        when (node) {
            is Map<*, *> -> {
                val properties = resolved["properties"] as? Map<*, *> ?: return
                properties.keys.forEach { declared += "$path.$it" }
                node.forEach { (key, value) ->
                    val property = properties[key]
                    written += "$path.$key"
                    // A payload schema of $defs is the module's, not the format's.
                    if (property is Map<*, *> && key != "\$defs") walk(property, value, "$path.$key", declared, written)
                }
            }

            is List<*> -> node.forEach { item ->
                (resolved["items"] as? Map<*, *>)?.let { walk(it, item, path, declared, written) }
            }
        }
    }

    private fun resolve(schema: Map<*, *>): Map<*, *> {
        val ref = schema["\$ref"] as? String ?: return schema
        val defs = ManifestSchema.schema["\$defs"] as Map<*, *>
        return defs[ref.removePrefix("#/\$defs/")] as Map<*, *>
    }
}
