description = "Wow eShop: the basket context's commands, events and value types"

dependencies {
    api(project(":api"))
    // The query fields of the generated contract code (EshopBasketContract.<Aggregate>.STATE__*), decision D259.
    api(project(":query-api"))
}
