description = "Wow eShop: the basket context's commands, events and value types"

plugins {
    id("me.ahoo.wow.api")
}

dependencies {
    // The domain module whose contract this api module carries (decision D363 ①).
    wowContract(project(":eshop-basket-domain"))
    // The commands' Jakarta constraints, which the host checks at admission (decision D106).
    api("jakarta.validation:jakarta.validation-api")
    // The query fields of the generated contract code (EshopBasketContract.<Aggregate>.STATE__*), decision D259.
    api("me.ahoo.wow:wow-query-api")
}
