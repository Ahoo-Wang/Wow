description = "Wow V10 example: the shop's cart and order aggregates"

dependencies {
    api(project(":shop-api"))
    // The aggregate tests run the domain on the kernel.
    testImplementation(project(":kernel"))
}
