use compensation_db;

sh.enableSharding("compensation_db");
sh.shardCollection("compensation_db.execution_failed_snapshot", { "_id" : "hashed" });
sh.shardCollection("compensation_db.execution_failed_event_stream", { "aggregateId" : "hashed" });
