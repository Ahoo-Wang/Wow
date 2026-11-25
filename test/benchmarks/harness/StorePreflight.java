/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bench.sh's store preflight: whether the address the nodes are given answers as this session's own store, through
 * the same resolution of `localhost` a node makes, before any node starts. Another tool can hold the store's port on
 * the host (kubefwd forwarding a cluster's MongoDB on 127.0.0.1:27017, say) while Docker still publishes the
 * container and reports it healthy; a node then meets that other server and fails only at its selection timeout.
 *
 *   java -cp '<server install>/lib/*' StorePreflight.java mongo <uri> <replica set> <container hostname>
 *   java -cp '<server install>/lib/*' StorePreflight.java postgres <jdbc url> <system identifier>
 *   java -cp '<server install>/lib/*' StorePreflight.java kafka <bootstrap servers> <cluster id>
 *
 * Exit 0 when it answers as expected, 3 when something else answers (printing what), 4 when nothing answers in
 * 5 s, 5 when the install lacks the store's client (nothing checked).
 */
public class StorePreflight {
    public static void main(String[] args) throws Exception {
        try {
            switch (args[0]) {
                case "mongo" -> mongo(args[1], args[2], args[3]);
                case "postgres" -> postgres(args[1], args[2]);
                case "kafka" -> kafka(args[1], args[2]);
                default -> throw new IllegalArgumentException("unknown store " + args[0]);
            }
        } catch (NoClassDefFoundError e) {
            System.out.println("not checked: the install has no " + args[0] + " client (" + e.getMessage() + ")");
            System.exit(5);
        }
    }

    private static void fail(int code, String what) {
        System.out.println(what);
        System.exit(code);
    }

    private static void mongo(String uri, String replicaSet, String hostname) {
        // Connected directly, not as a replica set: the driver would drop a member of another set, or a mongos, and
        // only time out; directly, it says what answers.
        var direct = uri.replaceAll("[?&]replicaSet=[^&]*", "");
        var settings = com.mongodb.MongoClientSettings.builder()
            .applyConnectionString(new com.mongodb.ConnectionString(direct))
            .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS)
                .mode(com.mongodb.connection.ClusterConnectionMode.SINGLE))
            .applyToSocketSettings(b -> b.connectTimeout(5, TimeUnit.SECONDS))
            .build();
        try (var client = com.mongodb.client.MongoClients.create(settings)) {
            var admin = client.getDatabase("admin");
            org.bson.Document hello;
            String host;
            try {
                hello = admin.runCommand(new org.bson.Document("hello", 1));
                host = admin.runCommand(new org.bson.Document("serverStatus", 1)).getString("host");
            } catch (com.mongodb.MongoTimeoutException e) {
                fail(4, "nothing answers as MongoDB at " + direct + " within 5 s");
                return;
            }
            String setName = hello.getString("setName");
            if (!replicaSet.equals(setName) || host == null || !host.startsWith(hostname)) {
                String what = "isdbgrid".equals(hello.getString("msg")) ? "a mongos" : "replica set " + setName;
                fail(3, "not this session's MongoDB: " + direct + " answers as " + what + " on host " + host
                    + ", where the session's is replica set " + replicaSet + " on host " + hostname);
            }
        }
    }

    private static void postgres(String url, String systemIdentifier) {
        java.sql.DriverManager.setLoginTimeout(5);
        try (var c = java.sql.DriverManager.getConnection(url, "postgres", "postgres");
             var rs = c.createStatement().executeQuery("select system_identifier::text from pg_control_system()")) {
            rs.next();
            String id = rs.getString(1);
            if (!systemIdentifier.equals(id)) {
                fail(3, "not this session's PostgreSQL: " + url + " answers with system identifier " + id
                    + ", where the session's is " + systemIdentifier);
            }
        } catch (java.sql.SQLException e) {
            fail(3, "not this session's PostgreSQL: " + url + " refused the session's login: " + e.getMessage());
        }
    }

    private static void kafka(String bootstrap, String clusterId) throws Exception {
        Map<String, Object> config = Map.of(
            "bootstrap.servers", bootstrap,
            "request.timeout.ms", 5000,
            "default.api.timeout.ms", 5000);
        try (var admin = org.apache.kafka.clients.admin.Admin.create(config)) {
            String id;
            try {
                id = admin.describeCluster().clusterId().get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                fail(4, "nothing answers as Kafka at " + bootstrap + " within 5 s");
                return;
            }
            if (!clusterId.equals(id)) {
                fail(3, "not this session's Kafka: " + bootstrap + " answers as cluster " + id
                    + ", where the session's is " + clusterId);
            }
        }
    }
}
