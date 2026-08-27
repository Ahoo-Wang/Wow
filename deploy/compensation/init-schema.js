use compensation_db;

db.execution_failed_snapshot.createIndex({ "state.recoverable" : "hashed" })
db.execution_failed_snapshot.createIndex({ "state.isRetryable" : "hashed" })
db.execution_failed_snapshot.createIndex({ "state.status" : "hashed" })
db.execution_failed_snapshot.createIndex({ "state.retryState.timeoutAt" : 1 })
db.execution_failed_snapshot.createIndex({ "state.retryState.nextRetryAt" : 1 })
db.execution_failed_snapshot.createIndex(
    { "tenantId" : 1, "state.status" : 1, "state.recoverable" : 1, "deleted" : 1, "_id" : "hashed" },
    { name : "tenantId_1_state.status_1_state.recoverable_1_deleted_1__id_hashed" }
)

sh.enableSharding("compensation_db");
sh.shardCollection("compensation_db.execution_failed_snapshot", { "_id" : "hashed" });
sh.shardCollection("compensation_db.execution_failed_event_stream", { "aggregateId" : "hashed" });
