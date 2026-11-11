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

package me.ahoo.wow.benchmark.search

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.wait
import me.ahoo.wow.api.query.dsl.countQuery
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.example.eshop.catalog.api.product.CreateProduct
import me.ahoo.wow.example.eshop.catalog.api.product.UpdateProduct
import me.ahoo.wow.example.eshop.catalog.server.CatalogServer
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * What Elasticsearch's indices of a store cost a document, for D411: the eShop catalog's server on its `search` profile
 * (its log on MongoDB, the products' snapshots and events on Elasticsearch), seeded in process with `--products`
 * products, each created and then updated through the node's [CommandClient], as a service.
 *
 * The server starts twice, as a catalog that has run before: its first start writes the first product, which creates
 * both indices; its second maps them, at a count of the product's snapshots and of its events, as a node maps the
 * indices it finds (an events index maps its bodies only when the index is there), then writes the others. Once both
 * indices hold every product's snapshot and both of its commits, each is refreshed, merged to one segment and flushed,
 * and `--out`'s `index.json` gets, per index: its documents, its store's bytes, Elasticsearch's indexing operations
 * and their time (`_stats`), and the `text` and `keyword` fields of its mapping.
 *
 * The catalog marks a product's name, description and reasons as prose (`@QueryText`); its ids, brand, category and
 * status are not. Run at two commits, the figures read what the mapping by the marker saves.
 *
 * Options: `--products` (100,000), `--concurrency` (64), `--elasticsearch` (its URL), `--out` (a directory); every
 * other argument goes to the catalog's server (`--spring.mongodb.uri=…`, `--server.port=…`).
 */
fun main(args: Array<String>) {
    val options = IndexOptions.parse(args)
    val elasticsearch = Elasticsearch(options.elasticsearch)
    catalog(options).use { context -> seed(commands(context), 0 until 1, options.concurrency) }
    elasticsearch.await(elasticsearch.productIndices().getValue(EVENTS), COMMITS.toLong())
    val figures = catalog(options).use { context ->
        val queries = context.getBean(NodeQueryApi::class.java).trusted()
        runBlocking {
            queries.snapshots(PRODUCT).count(countQuery { })
            queries.events(PRODUCT).count(countQuery { })
        }
        val started = System.nanoTime()
        seed(commands(context), 1 until options.products, options.concurrency)
        val indices = elasticsearch.productIndices()
        elasticsearch.await(indices.getValue(SNAPSHOT), options.products.toLong())
        elasticsearch.await(indices.getValue(EVENTS), options.products * COMMITS.toLong())
        val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND
        elasticsearch.figures(options, indices, seconds)
    }
    File(options.out).apply { mkdirs() }.resolve("index.json").writeText(figures)
    println(figures)
    exitProcess(0)
}

/** IndexFigures' own options, and the catalog server's arguments, every other one. */
internal class IndexOptions(
    val products: Int,
    val concurrency: Int,
    val elasticsearch: String,
    val out: String,
    val serverArgs: List<String>,
) {
    companion object {
        private val OWN = setOf("products", "concurrency", "elasticsearch", "out")

        fun parse(args: Array<String>): IndexOptions {
            val own = args.mapNotNull { arg ->
                val name = arg.removePrefix("--").substringBefore('=')
                if (arg.startsWith("--") && name in OWN) name to arg.substringAfter('=') else null
            }.toMap()
            return IndexOptions(
                products = own["products"]?.toInt() ?: DEFAULT_PRODUCTS,
                concurrency = own["concurrency"]?.toInt() ?: DEFAULT_CONCURRENCY,
                elasticsearch = own["elasticsearch"] ?: "http://127.0.0.1:19200",
                out = own["out"] ?: "build/index-figures",
                serverArgs = args.filter { arg -> arg.removePrefix("--").substringBefore('=') !in OWN },
            )
        }
    }
}

/** The catalog's server on its `search` profile, on [options]' Elasticsearch. */
private fun catalog(options: IndexOptions): ConfigurableApplicationContext {
    val args = options.serverArgs + "--spring.elasticsearch.uris=${options.elasticsearch}"
    return SpringApplication(CatalogServer::class.java)
        .apply { setAdditionalProfiles("search") }
        .run(*args.toTypedArray())
}

/** The node's commands, sent as this program's service. */
private fun commands(context: ConfigurableApplicationContext): CommandClient =
    context.getBean(CommandClient::class.java).of(Caller.ofService("index-figures"))

/** Creates and then updates each product of [range], [concurrency] at a time; a product's commits in order. */
private fun seed(client: CommandClient, range: IntRange, concurrency: Int) = runBlocking {
    val permits = Semaphore(concurrency)
    range.map { n ->
        async {
            permits.withPermit {
                val product = Products.of(n)
                client.wait(product.created, product.id)
                client.wait(product.updated, product.id)
            }
        }
    }.awaitAll()
    System.err.println("index-figures: seeded products ${range.first} to ${range.last}")
}

/**
 * The seeded products, the same at every run: a name of a few words, a description of a few sentences, a brand and a
 * category among a few hundred, a price; the update rewrites the name and description.
 */
internal object Products {
    private val WORDS = (
        "ceramic linen walnut steel cotton glass leather bamboo copper wool oak marble canvas felt brass slate " +
            "mug lamp chair table shelf basket bowl vase towel blanket kettle tray stool rug clock mirror " +
            "handmade sturdy light soft matte glossy small large round square tall wide warm cool quiet bright " +
            "kitchen bedroom garden office studio hallway bathroom porch dining living reading travel daily " +
            "keeps holds brings fits stands folds stacks pours lasts cleans warms dries carries lights rests"
        ).split(' ')

    class Product(val id: String, val created: CreateProduct, val updated: UpdateProduct)

    fun of(n: Int): Product {
        val random = Random(n)
        val id = "product-%07d".format(n)
        val brand = "brand-%03d".format(random.nextInt(BRANDS))
        val category = "category-%03d".format(random.nextInt(CATEGORIES))
        val price = BigDecimal.valueOf(random.nextLong(PRICE_CENTS_MIN, PRICE_CENTS_MAX), 2)
        return Product(
            id = id,
            created = CreateProduct(name(random), price, description(random), brand, category),
            updated = UpdateProduct(name(random), description(random), brand, category),
        )
    }

    private fun name(random: Random): String = words(random, NAME_WORDS).replaceFirstChar { it.uppercase() }

    private fun description(random: Random): String = (1..SENTENCES).joinToString(" ") {
        words(random, random.nextInt(SENTENCE_MIN, SENTENCE_MAX)).replaceFirstChar { it.uppercase() } + "."
    }

    private fun words(random: Random, count: Int): String = (1..count).joinToString(" ") { WORDS.random(random) }
}

/** The catalog's Elasticsearch, over its REST API. */
private class Elasticsearch(private val url: String) {
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    fun get(path: String): JsonNode = send(HttpRequest.newBuilder(URI.create(url + path)).GET())

    fun post(path: String): JsonNode =
        send(HttpRequest.newBuilder(URI.create(url + path)).POST(HttpRequest.BodyPublishers.noBody()))

    private fun send(request: HttpRequest.Builder): JsonNode {
        val response = http.send(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        check(status in 200..299) { "Elasticsearch answered $status: ${response.body()}" }
        return json.readTree(response.body())
    }

    /**
     * The product's snapshot and events indices, by kind (`[<prefix>_]wow_snapshot__…`, `…wow_events__…`), once both
     * are there: the sink creates its index at its first write.
     */
    fun productIndices(): Map<String, String> {
        repeat(AWAIT_STILL_SECONDS) {
            val names = get("/_cat/indices?format=json&h=index").values().map { it.path("index").asString() }
            val indices = listOf(SNAPSHOT, EVENTS).associateWith { kind ->
                names.singleOrNull { it.contains("wow_${kind}__") && it.endsWith("__product") }
            }
            if (indices.values.all { it != null }) return indices.mapValues { checkNotNull(it.value) }
            Thread.sleep(AWAIT_POLL_MILLIS)
        }
        error("No snapshot and events indices of the product in ${AWAIT_STILL_SECONDS}s")
    }

    /** Waits until [index] holds [documents], or its count stops growing for a minute. */
    fun await(index: String, documents: Long) {
        var last = -1L
        var still = 0
        while (true) {
            val count = get("/$index/_count").path("count").asLong()
            if (count >= documents) return
            if (count == last) still++ else still = 0
            check(still < AWAIT_STILL_SECONDS) { "$index holds $count of $documents documents, and no more" }
            last = count
            Thread.sleep(AWAIT_POLL_MILLIS)
        }
    }

    /** The figures of [indices], refreshed and merged to one segment first, as index.json's text. */
    fun figures(options: IndexOptions, indices: Map<String, String>, seconds: Double): String {
        val names = indices.values.joinToString(",")
        post("/$names/_refresh")
        post("/$names/_forcemerge?max_num_segments=1")
        post("/$names/_flush")
        // A shard's store size is cached for index.store.stats_refresh_interval (10 s by default).
        Thread.sleep(STORE_STATS_MILLIS)
        val stats = get("/$names/_stats/store,indexing").path("indices")
        val mappings = get("/$names/_mapping")
        val figures = indices.map { (kind, index) ->
            val primaries = stats.path(index).path("primaries")
            val indexing = primaries.path("indexing")
            val fields = FieldTypes().apply { visit(mappings.path(index).path("mappings")) }
            mapOf(
                "kind" to kind,
                "index" to index,
                "documents" to get("/$index/_count").path("count").asLong(),
                "storeBytes" to primaries.path("store").path("size_in_bytes").asLong(),
                "indexTotal" to indexing.path("index_total").asLong(),
                "indexTimeMillis" to indexing.path("index_time_in_millis").asLong(),
                "textFields" to fields.text,
                "keywordFields" to fields.keyword,
            )
        }
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapOf(
                "products" to options.products,
                "commits" to options.products * COMMITS,
                "seedSeconds" to seconds,
                "indices" to figures,
            ),
        )
    }
}

/** The `text` and `keyword` fields of a mapping, multi-fields included. */
internal class FieldTypes {
    var text = 0
        private set
    var keyword = 0
        private set

    fun visit(node: JsonNode) {
        when (node.path("type").asString("")) {
            "text" -> text++
            "keyword" -> keyword++
        }
        listOf("properties", "fields").forEach { child ->
            node.path(child).forEach { visit(it) }
        }
    }
}

/** The catalog's product, as its api names it. */
private val PRODUCT = AggregateType("eshop-catalog", "product")
private const val SNAPSHOT = "snapshot"
private const val EVENTS = "events"

/** The commits of each product: its creation and its update. */
private const val COMMITS = 2
private const val DEFAULT_PRODUCTS = 100_000
private const val DEFAULT_CONCURRENCY = 64
private const val NAME_WORDS = 3
private const val SENTENCES = 4
private const val SENTENCE_MIN = 8
private const val SENTENCE_MAX = 16
private const val BRANDS = 200
private const val CATEGORIES = 50
private const val PRICE_CENTS_MIN = 199L
private const val PRICE_CENTS_MAX = 99_999L
private const val AWAIT_STILL_SECONDS = 60
private const val AWAIT_POLL_MILLIS = 1_000L
private const val NANOS_PER_SECOND = 1e9
private const val STORE_STATS_MILLIS = 11_000L
private val REQUEST_TIMEOUT = java.time.Duration.ofMinutes(30)
