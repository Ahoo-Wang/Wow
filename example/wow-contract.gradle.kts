/*
 * The peer contract of an example domain module (decision D234), as the Gradle plugin `me.ahoo.wow` provides it to an
 * application's module: the plugin cannot be applied to a project of the build that produces it (DX gap G6), so
 * example/wow-manifest-fragment.gradle.kts applies this script to every domain module after completing its fragment.
 *
 * - `generateWowContract` filters the completed fragment down to the contract of the module's contexts
 *   (`ManifestTools.generateContract`), which the consumable configuration `wowContractElements` offers
 *   (Category=documentation, DocsType=wow-contract).
 * - Pairing: each project the module declares in `api(...)`, but Wow's own (`frameworkProjects`, which a user's build
 *   takes as module dependencies), is its api module. The api module gets the resolvable configuration `wowContract`
 *   on the domain's contract, and `assembleWowContract` (`ManifestTools.assembleContract`) keeps the contexts whose
 *   command and event classes are all its own, fails when a context has only some of them, and writes
 *   `META-INF/wow/contract.json`, which the api's jar includes. A service knows a peer context by depending on its api.
 *
 * The tools run as in example/wow-manifest-fragment.gradle.kts: in a class loader of their own over `:manifest-tools`.
 */
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/** Wow's own projects a domain module of this build may declare in `api(...)`: no api module of a context. */
val frameworkProjects = setOf(":api", ":kernel", ":manifest", ":query-api")

/** Calls `ManifestTools` in a class loader over the tools; an object, so a task action captures no script. */
object WowContractTools {
    fun call(tools: Set<File>, method: String, vararg arguments: Any?): Any? =
        URLClassLoader(tools.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use {
            val function = Class.forName("me.ahoo.wow.manifest.tools.ManifestTools", true, it).methods
                .single { function -> function.name == method }
            try {
                function.invoke(null, *arguments)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
}

fun AttributeContainer.wowContract(objects: ObjectFactory) {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
    attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named("wow-contract"))
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
}

/** The `wowManifestTools` configuration of [project]: `:manifest-tools` and its runtime class path. */
fun toolsOf(project: Project): Configuration = project.configurations.findByName("wowManifestTools")
    ?: project.configurations.create("wowManifestTools") {
        description = "The tools the Manifest tasks run: :manifest-tools and its runtime class path."
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
        }
    }.also { project.dependencies.add(it.name, project.project(":manifest-tools")) }

val domainTools = toolsOf(project)
val completedFragments = files(tasks.named("generateWowManifestFragment")).asFileTree
    .matching { include("META-INF/wow/manifest/*.json") }
val contractFile = layout.buildDirectory.file("wow/contract.json")

val generateWowContract = tasks.register("generateWowContract") {
    group = "wow"
    description = "Filters the module's Manifest fragment down to the contract its api modules carry."
    val fragments = completedFragments
    val tools: FileCollection = files(domainTools)
    val output = contractFile
    inputs.files(fragments).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("fragments")
    inputs.files(tools).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("tools")
    outputs.file(output)
    outputs.cacheIf { true }
    doLast {
        WowContractTools.call(tools.files, "generateContract", fragments.files.toList(), output.get().asFile)
    }
}

configurations.create("wowContractElements") {
    description = "The peer contract of the module's contexts, which its api modules carry."
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { wowContract(objects) }
    outgoing.artifact(contractFile) { builtBy(generateWowContract) }
}

/** Makes [api] carry the contract of the domain module [domain]; several domain modules may pair with one api. */
fun pairApi(api: Project, domain: String) {
    api.pluginManager.withPlugin("java") {
        // Its consumers compile against its classes, never its jar, which waits for the domain's contract.
        api.pluginManager.apply("java-library")
        val contracts = api.configurations.findByName("wowContract") ?: assembly(api)
        api.dependencies.add(contracts.name, api.project(domain))
    }
}

fun assembly(api: Project): Configuration {
    val contracts = api.configurations.create("wowContract") {
        description = "The peer contracts of the domain modules whose api module this is."
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        attributes { wowContract(api.objects) }
    }
    val apiTools = toolsOf(api)
    val main = api.extensions.getByType<SourceSetContainer>().named("main").get()
    val assemble = api.tasks.register("assembleWowContract") {
        group = "wow"
        description = "Takes the contracts of this api module's contexts from the domain modules that declare it."
        val inputContracts: FileCollection = api.files(contracts)
        val classes: FileCollection = main.output.classesDirs
        val tools: FileCollection = api.files(apiTools)
        val owner = api.path
        val output = api.layout.buildDirectory.dir("wow/contract")
        inputs.files(inputContracts).withPathSensitivity(PathSensitivity.NONE).withPropertyName("contracts")
        inputs.files(classes).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("classes")
        inputs.files(tools).withNormalizer(ClasspathNormalizer::class.java).withPropertyName("tools")
        inputs.property("owner", owner)
        outputs.dir(output)
        outputs.cacheIf { true }
        doLast {
            val root = output.get().asFile.apply { deleteRecursively() }
            WowContractTools.call(
                tools.files,
                "assembleContract",
                inputContracts.files.toList(),
                classes.files.toList(),
                owner,
                File(root, "META-INF/wow/contract.json"),
            )
        }
    }
    api.tasks.named<Jar>("jar") { from(assemble) }
    return contracts
}

val domainPath = path
configurations.named("api") {
    dependencies.withType<ProjectDependency>().configureEach {
        if (path !in frameworkProjects) {
            pairApi(project.project(path), domainPath)
        }
    }
}
