description = "Wow eShop: the payment context's aggregates"

// The domain module (decision D363 ①): the plugin runs KSP with wow-compiler over the aggregates (kernel models, their
// binding index, D107, and the module's Manifest fragment), completes the fragment with the schemas of the types,
// offers the module's contract to its api module, and adds wow-kernel, which the generated code builds on (D149).
plugins {
    id("me.ahoo.wow")
}

wow {
    service = "eshop-payment-service"
}

dependencies {
    api(project(":eshop-payment-api"))
    // The ordering's commands, which the order-payment saga sends to the ordering context, and its order view.
    implementation(project(":eshop-ordering-api"))
    // The ordering's shared cache of its orders, which the gateway saga reads: the interface alone, no dependency.
    implementation("me.ahoo.wow:wow-cocache-api")
    testImplementation("me.ahoo.wow:wow-test")
    // The Manifest fragment the compiler writes, which the tests read (the kernel does not bring :manifest, D231).
    testImplementation("me.ahoo.wow:wow-manifest")
    // The sagas' suspending handler and an `@OnRejected` handler's binding are invoked from a coroutine.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
}
