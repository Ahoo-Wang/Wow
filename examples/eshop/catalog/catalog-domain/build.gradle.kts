description = "Wow eShop: the catalog context's aggregates"

// The domain module (decision D363 ①): the plugin runs KSP with wow-compiler over the aggregates (kernel models, their
// binding index, D107, and the module's Manifest fragment), completes the fragment with the schemas of the types,
// offers the module's contract to its api module, and adds wow-kernel, which the generated code builds on (D149).
plugins {
    id("me.ahoo.wow")
}

wow {
    service = "eshop-catalog-service"
}

dependencies {
    api(project(":eshop-catalog-api"))
    // @QueryIndex on the product's state: the storefront's filters and sorts stay index scans.
    implementation("me.ahoo.wow:wow-query-api")
    testImplementation("me.ahoo.wow:wow-test")
}
