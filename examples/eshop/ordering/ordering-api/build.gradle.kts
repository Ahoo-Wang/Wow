description = "Wow eShop: the ordering context's commands, events and value types"

dependencies {
    api(project(":api"))
    // The commands' Jakarta constraints, which the host checks at admission (decision D106).
    api("jakarta.validation:jakarta.validation-api")
    // The query fields of the generated contract code (EshopOrderingContract.<Aggregate>.STATE__*), decision D259.
    api(project(":query-api"))
}
