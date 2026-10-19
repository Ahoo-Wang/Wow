description = "Wow V10 eShop: the ordering context's commands, events and value types"

dependencies {
    api(project(":api"))
}

// SPIKE D234: a publication of the api alone, to check that the published jar carries the domain's contract.
apply(plugin = "maven-publish")
configure<PublishingExtension> {
    publications {
        create<MavenPublication>("spikeContract") {
            groupId = "me.ahoo.wow.spike"
            from(components["java"])
        }
    }
}
