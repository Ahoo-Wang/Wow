description = "Wow V10 eShop: the payment context's commands, events and value types"

dependencies {
    api(project(":api"))
    // The query fields of the generated contract code (EshopPaymentContract.<Aggregate>.STATE__*), decision D259.
    api(project(":query-api"))
}
