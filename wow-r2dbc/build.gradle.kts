dependencies {
    api(project(":wow-core"))
    implementation("io.netty:netty-all")
    implementation("me.ahoo.cosid:cosid-core")
    api("io.r2dbc:r2dbc-spi")
    api("io.r2dbc:r2dbc-pool")
    api("io.r2dbc:r2dbc-proxy")
    testImplementation(project(":wow-tck"))
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation("org.mariadb:r2dbc-mariadb")
}
