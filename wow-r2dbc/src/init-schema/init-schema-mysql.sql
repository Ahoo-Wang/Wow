# Naming conventions
# {aggregate_name}_{event_stream}
# {aggregate_name}_{snapshot}

create database wow_db;
use wow_db;

# ----- schema template -----
create table aggregate_event_stream
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table aggregate_snapshot
(
    aggregate_id     char(15)        not null primary key,
    tenant_id        char(15)        not null,
    version          int unsigned    not null comment 'aggregate version',
    state_type       varchar(255)    not null comment 'aggregate state type',
    state            longtext        not null comment 'aggregate state',
    event_id    char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time  bigint unsigned not null default 0,
    snapshot_time    bigint unsigned not null,
    deleted          tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;
# ----- schema template -----

# Test
create table mock_aggregate_event_stream_0
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table mock_aggregate_event_stream_1
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table mock_aggregate_event_stream_2
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table mock_aggregate_event_stream_3
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table mock_aggregate_snapshot
(
    aggregate_id    char(15)        not null primary key,
    tenant_id       char(15)        not null,
    version         int unsigned    not null comment 'aggregate version',
    state_type      varchar(255)    not null comment 'aggregate state type',
    state           longtext        not null comment 'aggregate state',
    event_id   char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time bigint unsigned not null default 0,
    snapshot_time   bigint unsigned not null,
    deleted         tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table eventsourcing_mock_aggregate_event_stream_0
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table eventsourcing_mock_aggregate_event_stream_1
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table eventsourcing_mock_aggregate_event_stream_2
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table eventsourcing_mock_aggregate_event_stream_3
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;


# Test Modeling Command Aggregate Processor Spec
create table modeling_command_mock_aggregate_event_stream_0
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table modeling_command_mock_aggregate_event_stream_1
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table modeling_command_mock_aggregate_event_stream_2
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table modeling_command_mock_aggregate_event_stream_3
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table modeling_command_mock_aggregate_snapshot
(
    aggregate_id    char(15)        not null primary key,
    tenant_id       char(15)        not null,
    version         int unsigned    not null comment 'aggregate version',
    state_type      varchar(255)    not null comment 'aggregate state type',
    state           longtext        not null comment 'aggregate state',
    event_id   char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time bigint unsigned not null default 0,
    create_time     bigint unsigned not null,
    deleted         tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;
# example

create table order_event_stream
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_event_stream_0
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_event_stream_1
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_event_stream_2
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_event_stream_3
(
    id           char(15)        not null comment 'event stream id' primary key,
    aggregate_id char(15)        not null,
    tenant_id    char(15)        not null,
    request_id   char(15)        not null,
    command_id   char(15)        not null,
    version      int unsigned    not null,
    header       mediumtext      not null,
    body         longtext        not null,
    size         int unsigned    not null,
    create_time  bigint unsigned not null,
    constraint u_idx_aggregate_id_version
        unique (aggregate_id, version),
    constraint u_idx_request_id
        unique (request_id),
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_snapshot
(
    aggregate_id    char(15)        not null primary key,
    tenant_id       char(15)        not null,
    version         int unsigned    not null comment 'aggregate version',
    state_type      varchar(255)    not null comment 'aggregate state type',
    state           longtext        not null comment 'aggregate state',
    event_id   char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time bigint unsigned not null default 0,
    snapshot_time   bigint unsigned not null,
    deleted         tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_snapshot_0
(
    aggregate_id    char(15)        not null primary key,
    tenant_id       char(15)        not null,
    version         int unsigned    not null comment 'aggregate version',
    state_type      varchar(255)    not null comment 'aggregate state type',
    state           longtext        not null comment 'aggregate state',
    event_id   char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time bigint unsigned not null default 0,
    snapshot_time   bigint unsigned not null,
    deleted         tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_snapshot_1
(
    aggregate_id    char(15)        not null primary key,
    tenant_id       char(15)        not null,
    version         int unsigned    not null comment 'aggregate version',
    state_type      varchar(255)    not null comment 'aggregate state type',
    state           longtext        not null comment 'aggregate state',
    event_id   char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time bigint unsigned not null default 0,
    snapshot_time   bigint unsigned not null,
    deleted         tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_snapshot_2
(
    aggregate_id    char(15)        not null primary key,
    tenant_id       char(15)        not null,
    version         int unsigned    not null comment 'aggregate version',
    state_type      varchar(255)    not null comment 'aggregate state type',
    state           longtext        not null comment 'aggregate state',
    event_id   char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time bigint unsigned not null default 0,
    snapshot_time   bigint unsigned not null,
    deleted         tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;

create table order_snapshot_3
(
    aggregate_id    char(15)        not null primary key,
    tenant_id       char(15)        not null,
    version         int unsigned    not null comment 'aggregate version',
    state_type      varchar(255)    not null comment 'aggregate state type',
    state           longtext        not null comment 'aggregate state',
    event_id   char(15)        not null default '',
    first_event_time bigint unsigned not null default 0,
    event_time bigint unsigned not null default 0,
    snapshot_time   bigint unsigned not null,
    deleted         tinyint(1)      not null default 0,
    key idx_tenant_id (tenant_id)
)
    collate = utf8mb4_bin;
