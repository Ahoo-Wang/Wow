// The wow-service chart the plugin renders (generateWowKubernetes), from the one copy the repository keeps,
// deploy/helm/wow-service: the files `helm package` takes (not those its .helmignore names), zipped into the plugin's
// resources at me/ahoo/wow/gradle/helm/wow-service.zip, under wow-service/. A chart whose Chart.yaml is not of the
// plugin's version is refused, so the chart a plugin renders is that plugin's. Applied by compiler/gradle-plugin and by
// build-logic/wow-gradle-plugin, which compiles the same sources; each sets the two extra properties first.
val chartDirectory = extra["wowChartDirectory"] as File
val pluginVersion = extra["wowChartVersion"] as String

// .helmignore's patterns as Gradle's: a directory (`tests/`) with everything in it, a file pattern at any depth.
val ignored = File(chartDirectory, ".helmignore").readLines().map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { if (it.endsWith("/")) "$it**" else "**/$it" }

val chart = tasks.register<Zip>("wowServiceChart") {
    description = "Zips the wow-service chart into the plugin's resources."
    from(chartDirectory) {
        into("wow-service")
        exclude(ignored)
    }
    archiveFileName.set("wow-service.zip")
    destinationDirectory.set(layout.buildDirectory.dir("wow-service-chart/me/ahoo/wow/gradle/helm"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    inputs.property("pluginVersion", pluginVersion)
    val chartYaml = File(chartDirectory, "Chart.yaml")
    doFirst {
        val version = Regex("""(?m)^version:\s*(\S+)\s*$""").find(chartYaml.readText())?.groupValues?.get(1)
        check(version == pluginVersion) {
            "$chartYaml is of version $version, not the plugin's $pluginVersion: `pnpm set-version` writes both."
        }
    }
}

the<SourceSetContainer>().named("main") {
    resources.srcDir(files(layout.buildDirectory.dir("wow-service-chart")).builtBy(chart))
}
