// use wow_db;
// event stream
db.createCollection("event_stream");
db.event_stream.createIndex({aggregateId: "hashed"});
db.event_stream.createIndex({aggregateId: 1,version:1},{unique:true});
// db.event_stream.createIndex({requestId:1},{unique:true});
db.event_stream.createIndex({ aggregateId:1,requestId: 1 }, { unique: true });
db.event_stream.createIndex({tenantId: "hashed"});

// snapshot

db.createCollection("snapshot");
db.snapshot.createIndex({tenantId: "hashed"});

// sharding
sh.enableSharding("wow_db");
sh.shardCollection("wow_db.snapshot", { "_id" : "hashed" });
sh.shardCollection("wow_db.event_stream", { "aggregateId" : "hashed" });


sh.enableSharding("compensation_db");
db.getSiblingDB("compensation_db").execution_failed_snapshot.createIndex(
    { "tenantId" : 1, "state.status" : 1, "state.recoverable" : 1, "deleted" : 1, "_id" : "hashed" },
    { name : "tenantId_1_state.status_1_state.recoverable_1_deleted_1__id_hashed" }
)
sh.shardCollection("compensation_db.execution_failed_snapshot", { "_id" : "hashed" });
sh.shardCollection("compensation_db.execution_failed_event_stream", { "aggregateId" : "hashed" });
