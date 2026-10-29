description = "Wow ops: the commands, events and value types of the ops context (roadmap 6.3f, 7.1a)"

plugins {
    id("me.ahoo.wow.api")
}

// The records each service publishes of its failure ledger (decision D141) are read from their JSON with this
// context's own types (ParkedEntry, ParkedResolution, decision D304); the ledger's system commands it sends back are
// :api's (me.ahoo.wow.api.ledger).
dependencies {
    // The domain module whose contract this api module carries (decision D363 ①).
    wowContract(project(":ops-domain"))
}
