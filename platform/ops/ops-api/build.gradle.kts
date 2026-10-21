description = "Wow ops: the commands, events and value types of the ops context (roadmap 6.3f, 7.1a)"

// The records each service publishes of its failure ledger, RecordParked and RecordResolved, are :api's
// (me.ahoo.wow.api.ledger, decision D141): the runtime sends them, and this context decides them.
dependencies {
    api(project(":api"))
}
