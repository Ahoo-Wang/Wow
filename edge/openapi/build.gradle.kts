description = "Wow OpenAPI: the OpenAPI 3.1 document of a service's public routes, from its Manifest alone; plain data"

dependencies {
    api(project(":manifest"))
    // The tests write the document as JSON, and check its schemas against the JSON Schema 2020-12 meta-schema.
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation(libs.json.schema.validator)
    // The snapshot tests document the eShop's services from their Manifest fragments (decision D159).
    testImplementation(project(":eshop-catalog-domain"))
    testImplementation(project(":eshop-basket-domain"))
    testImplementation(project(":eshop-ordering-domain"))
    testImplementation(project(":eshop-inventory-domain"))
    testImplementation(project(":eshop-payment-domain"))
}
