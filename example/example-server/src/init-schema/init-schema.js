// use wow_example_db;
sh.enableSharding("wow_example_db");
sh.shardCollection("wow_example_db.order_snapshot", { "_id" : "hashed" });
sh.shardCollection("wow_example_db.order_event_stream", { "aggregateId" : "hashed" });
sh.shardCollection("wow_example_db.cart_snapshot", { "_id" : "hashed" });
sh.shardCollection("wow_example_db.cart_event_stream", { "aggregateId" : "hashed" });