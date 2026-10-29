description = "Wow eShop: the inventory context's aggregates"

// The domain module (decision D363 ①): the plugin runs KSP with wow-compiler over the aggregates (kernel models, their
// binding index, D107, and the module's Manifest fragment), completes the fragment with the schemas of the types,
// offers the module's contract to its api module, and adds wow-kernel, which the generated code builds on (D149).
plugins {
    id("me.ahoo.wow")
}

wow {
    service = "eshop-inventory-service"
}

dependencies {
    api(project(":eshop-inventory-api"))
    testImplementation("me.ahoo.wow:wow-test")
}
