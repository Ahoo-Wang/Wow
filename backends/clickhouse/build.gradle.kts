description = "Wow ClickHouse: the ClickHouse Sink and the dataset query backend"

// The skeleton of work package 6.00; the dataset backend comes with 6.4a, the Sink with 6.4b (decision D142).
dependencies {
    api(project(":spi"))
    api(project(":query"))
    api(libs.clickhouse.client.v2)
}
