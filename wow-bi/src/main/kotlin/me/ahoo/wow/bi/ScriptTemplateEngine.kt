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

package me.ahoo.wow.bi

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.expansion.TableNaming.toDistributedTableName
import me.ahoo.wow.bi.expansion.TableNaming.toTopicName

@Suppress("LongMethod")
object ScriptTemplateEngine {
    const val DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9093"
    const val DEFAULT_TOPIC_PREFIX = Wow.WOW_PREFIX
    val DEFAULT_MESSAGE_HEADER_SQL_TYPE = MessageHeaderSqlType.MAP

    fun renderGlobal(): String {
        return """
            CREATE DATABASE IF NOT EXISTS bi_db ON CLUSTER '{cluster}';
            CREATE DATABASE IF NOT EXISTS bi_db_consumer ON CLUSTER '{cluster}';
        """.trimIndent()
    }

    fun renderClear(namedAggregate: NamedAggregate, expansionTables: List<String>): String {
        return buildString {
            appendLine("------------------command------------------")
            val commandDistributedTableName = namedAggregate.toDistributedTableName("command")
            appendLine("DROP TABLE IF EXISTS bi_db.$commandDistributedTableName ON CLUSTER '{cluster}' SYNC;")
            appendLine("DROP TABLE IF EXISTS bi_db.${commandDistributedTableName}_local ON CLUSTER '{cluster}' SYNC;")
            appendLine(
                "DROP TABLE IF EXISTS bi_db_consumer.${commandDistributedTableName}_queue ON CLUSTER '{cluster}' SYNC;"
            )
            appendLine(
                "DROP TABLE IF EXISTS bi_db_consumer.${commandDistributedTableName}_consumer ON CLUSTER '{cluster}' SYNC;"
            )
            appendLine("------------------command------------------")
            appendLine("------------------state------------------")
            val stateDistributedTableName = namedAggregate.toDistributedTableName("state")
            appendLine("DROP TABLE IF EXISTS bi_db.$stateDistributedTableName ON CLUSTER '{cluster}' SYNC;")
            appendLine("DROP TABLE IF EXISTS bi_db.${stateDistributedTableName}_event ON CLUSTER '{cluster}' SYNC;")
            appendLine("DROP TABLE IF EXISTS bi_db.${stateDistributedTableName}_local ON CLUSTER '{cluster}' SYNC;")
            appendLine(
                "DROP TABLE IF EXISTS bi_db_consumer.${stateDistributedTableName}_queue ON CLUSTER '{cluster}' SYNC;"
            )
            appendLine(
                "DROP TABLE IF EXISTS bi_db_consumer.${stateDistributedTableName}_consumer ON CLUSTER '{cluster}' SYNC;"
            )
            appendLine("------------------state------------------")
            appendLine("------------------stateLast------------------")
            val stateLastDistributedTableName = namedAggregate.toDistributedTableName("state_last")
            appendLine("DROP TABLE IF EXISTS bi_db.$stateLastDistributedTableName ON CLUSTER '{cluster}' SYNC;")
            appendLine("DROP TABLE IF EXISTS bi_db.${stateLastDistributedTableName}_local ON CLUSTER '{cluster}' SYNC;")
            appendLine(
                "DROP TABLE IF EXISTS bi_db_consumer.${stateLastDistributedTableName}_consumer ON CLUSTER '{cluster}' SYNC;"
            )
            appendLine("------------------stateLast------------------")
            appendLine("------------------expansion------------------")
            expansionTables.forEach {
                appendLine("DROP TABLE IF EXISTS bi_db.$it ON CLUSTER '{cluster}' SYNC;")
            }
            appendLine("------------------expansion------------------")
        }
    }

    fun renderCommand(
        namedAggregate: NamedAggregate,
        kafkaBootstrapServers: String = DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
        topicPrefix: String = DEFAULT_TOPIC_PREFIX,
        headerType: MessageHeaderSqlType = DEFAULT_MESSAGE_HEADER_SQL_TYPE
    ): String {
        val suffix = "command"
        val topic = namedAggregate.toTopicName(topicPrefix, suffix)
        val distributedTableName = namedAggregate.toDistributedTableName(suffix)
        val localTableName = "${distributedTableName}_local"
        val queueTableName = "${distributedTableName}_queue"
        val consumerTableName = "${distributedTableName}_consumer"
        return """
            CREATE TABLE IF NOT EXISTS bi_db."$localTableName" ON CLUSTER '{cluster}'
            (
                id             String,
                context_name   String,
                aggregate_name String,
                name           String,
                header         ${headerType.sqlType},
                aggregate_id   String,
                tenant_id      String,
                owner_id       String,
                space_id       String,
                request_id     String,
                aggregate_version Nullable(UInt32),
                is_create      Bool,
                allow_create   Bool,
                body_type      String,
                body           String,
                create_time    DateTime('Asia/Shanghai')
            ) ENGINE = ReplicatedMergeTree(
                    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
                  PARTITION BY toYYYYMM(create_time)
                  ORDER BY id
            ;
            
            CREATE TABLE IF NOT EXISTS bi_db."$distributedTableName" ON CLUSTER '{cluster}'
                AS bi_db."$localTableName"
                    ENGINE = Distributed('{cluster}', bi_db, "$localTableName", sipHash64(aggregate_id));
            
            CREATE TABLE IF NOT EXISTS bi_db_consumer."$queueTableName" ON CLUSTER '{cluster}'
            (
                data String
            ) ENGINE = Kafka('$kafkaBootstrapServers', '$topic', 'clickhouse_$consumerTableName', 'JSONAsString');
            
            CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer."$consumerTableName"
                        ON CLUSTER '{cluster}'
                        TO bi_db."$distributedTableName"
            AS
            SELECT JSONExtractString(data, 'id')            AS id,
                   JSONExtractString(data, 'contextName')   AS context_name,
                   JSONExtractString(data, 'aggregateName') AS aggregate_name,
                   JSONExtractString(data, 'name')          AS name,
                   JSONExtract(data, 'header','${headerType.sqlType}')        AS header,
                   JSONExtractString(data, 'aggregateId')   AS aggregate_id,
                   JSONExtractString(data, 'tenantId')      AS tenant_id,
                   JSONExtractString(data, 'ownerId')       AS owner_id,
                   JSONExtractString(data, 'spaceId')       AS space_id,
                   JSONExtractString(data, 'requestId')     AS request_id,
                   JSONExtract(data, 'aggregateVersion',
                               'Nullable(UInt32)')          AS aggregate_version,
                   JSONExtractBool(data, 'isCreate')        AS is_create,
                   JSONExtractBool(data, 'allowCreate')     AS allow_create,
                   JSONExtractString(data, 'bodyType')      AS body_type,
                   JSONExtractString(data, 'body')          AS body,
                   toDateTime64(
                           JSONExtractUInt(data, 'createTime') / 1000.0
                       , 3, 'Asia/Shanghai')                AS create_time
            FROM bi_db_consumer."$queueTableName"
            ;
        """.trimIndent()
    }

    fun renderStateEvent(
        namedAggregate: NamedAggregate,
        kafkaBootstrapServers: String = DEFAULT_KAFKA_BOOTSTRAP_SERVERS,
        topicPrefix: String = DEFAULT_TOPIC_PREFIX,
        headerType: MessageHeaderSqlType = DEFAULT_MESSAGE_HEADER_SQL_TYPE
    ): String {
        val suffix = "state"
        val topic = namedAggregate.toTopicName(topicPrefix, suffix)
        val distributedTableName = namedAggregate.toDistributedTableName(suffix)
        val localTableName = "${distributedTableName}_local"
        val eventTableName = "${distributedTableName}_event"
        val queueTableName = "${distributedTableName}_queue"
        val consumerTableName = "${distributedTableName}_consumer"
        return """
            CREATE TABLE IF NOT EXISTS bi_db."$localTableName" ON CLUSTER '{cluster}'
            (
                id               String,
                context_name     String,
                aggregate_name   String,
                header           ${headerType.sqlType},
                aggregate_id     String,
                tenant_id        String,
                owner_id         String,
                space_id         String,
                command_id       String,
                request_id       String,
                version          UInt32,
                state            String,
                body             Array(String),
                first_operator   String,
                first_event_time DateTime('Asia/Shanghai'),
                create_time      DateTime('Asia/Shanghai'),
                deleted          Bool
            ) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
                                                    '{replica}', version)
                  PARTITION BY toYYYYMM(create_time)
                  ORDER BY (aggregate_id, version)
            ;
            
            CREATE TABLE IF NOT EXISTS bi_db."$distributedTableName" ON CLUSTER '{cluster}'
                AS bi_db."$localTableName"
                    ENGINE = Distributed('{cluster}', bi_db, "$localTableName", sipHash64(aggregate_id));
            
            CREATE TABLE IF NOT EXISTS bi_db_consumer."$queueTableName" ON CLUSTER '{cluster}'
            (
                data String
            ) ENGINE = Kafka('$kafkaBootstrapServers', '$topic', 'clickhouse_$consumerTableName', 'JSONAsString');
            
            CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer."$consumerTableName"
                        ON CLUSTER '{cluster}'
                        TO bi_db."$distributedTableName"
            AS
            SELECT JSONExtractString(data, 'id')                      AS id,
                   JSONExtractString(data, 'contextName')             AS context_name,
                   JSONExtractString(data, 'aggregateName')           AS aggregate_name,
                   JSONExtract(data, 'header', '${headerType.sqlType}') AS header,
                   JSONExtractString(data, 'aggregateId')             AS aggregate_id,
                   JSONExtractString(data, 'tenantId')                AS tenant_id,
                   JSONExtractString(data, 'ownerId')                 AS owner_id,
                   JSONExtractString(data, 'spaceId')                 AS space_id,
                   JSONExtractString(data, 'commandId')               AS command_id,
                   JSONExtractString(data, 'requestId')               AS request_id,
                   JSONExtractUInt(data, 'version')                   AS version,
                   JSONExtractString(data, 'state')                   AS state,
                   JSONExtractArrayRaw(data, 'body')                  AS body,
                   JSONExtractString(data, 'firstOperator')           AS first_operator,
                   toDateTime64(JSONExtractUInt(data, 'firstEventTime') / 1000.0
                       , 3, 'Asia/Shanghai')                          AS first_event_time,
                   toDateTime64(JSONExtractUInt(data, 'createTime') / 1000.0
                       , 3, 'Asia/Shanghai')                          AS create_time,
                   JSONExtractBool(data, 'deleted')                   AS deleted
            FROM bi_db_consumer."$queueTableName"
            ;
            
            CREATE VIEW IF NOT EXISTS bi_db."$eventTableName" ON CLUSTER '{cluster}'
            AS
            WITH arrayJoin(arrayZip(arrayEnumerate(body),body)) AS events
            SELECT id,
                   context_name,
                   aggregate_name,
                   header,
                   aggregate_id,
                   tenant_id,
                   owner_id,
                   space_id,
                   command_id,
                   request_id,
                   version,
                   state,
                   events.1                                    AS event_sequence,
                   JSONExtract(events.2, 'id', 'String')       AS event_id,
                   JSONExtract(events.2, 'name', 'String')     AS event_name,
                   JSONExtract(events.2, 'revision', 'String') AS event_revision,
                   JSONExtract(events.2, 'bodyType', 'String') AS event_body_type,
                   JSONExtract(events.2, 'body', 'String')     AS event_body,
                   first_operator,
                   first_event_time,
                   create_time,
                   deleted
            FROM bi_db."$distributedTableName";
        """.trimIndent()
    }

    fun renderStateLast(
        namedAggregate: NamedAggregate,
        headerType: MessageHeaderSqlType = DEFAULT_MESSAGE_HEADER_SQL_TYPE
    ): String {
        val stateDistributedTableName = namedAggregate.toDistributedTableName("state")
        val distributedTableName = namedAggregate.toDistributedTableName("state_last")
        val localTableName = "${distributedTableName}_local"
        val consumerTableName = "${distributedTableName}_consumer"
        return """
            CREATE TABLE IF NOT EXISTS bi_db."$localTableName" ON CLUSTER '{cluster}'
            (
                id               String,
                context_name     String,
                aggregate_name   String,
                header           ${headerType.sqlType},
                aggregate_id     String,
                tenant_id        String,
                owner_id         String,
                space_id         String,
                command_id       String,
                request_id       String,
                version          UInt32,
                state            String,
                body             Array(String),
                first_operator   String,
                first_event_time DateTime('Asia/Shanghai'),
                create_time      DateTime('Asia/Shanghai'),
                deleted          Bool
            ) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
                                                    '{replica}', version)
                  PARTITION BY sipHash64(aggregate_id) % 8
                  ORDER BY (aggregate_id)
            ;
            
            CREATE TABLE IF NOT EXISTS bi_db."$distributedTableName" ON CLUSTER '{cluster}'
                AS bi_db."$localTableName"
                    ENGINE = Distributed('{cluster}', bi_db, "$localTableName", sipHash64(aggregate_id));
            
            CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer."$consumerTableName"
                        ON CLUSTER '{cluster}'
                        TO bi_db."$distributedTableName"
            AS
            SELECT *
            FROM bi_db."$stateDistributedTableName"
            ;
        """.trimIndent()
    }
}
