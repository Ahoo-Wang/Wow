CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtractArrayRaw(state, 'items') AS items,
JSONExtractArrayRaw(state, 'set') AS set,
JSONExtractArrayRaw(state, 'nestedList') AS nested_list,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_items ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractArrayRaw(state, 'items')) AS items
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
JSONExtract(items,'id', 'String') AS items_id,
JSONExtract(items,'name', 'String') AS items_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_set ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractArrayRaw(state, 'set')) AS set
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
JSONExtract(set,'id', 'String') AS set_id,
JSONExtract(set,'name', 'String') AS set_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_nested_list ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractArrayRaw(state, 'nestedList')) AS nested_list
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
JSONExtract(nested_list,'id', 'String') AS nested_list_id,
JSONExtract(nested_list,'name', 'String') AS nested_list_name,
JSONExtractArrayRaw(nested_list, 'list') AS nested_list_list,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;

CREATE VIEW IF NOT EXISTS bi_db.bi_aggregate_state_last_root_nested_list_list ON CLUSTER '{cluster}' AS
WITH
JSONExtractString(state,'item') AS item,
JSONExtractString(state,'nested') AS nested,
JSONExtractString(nested,'child') AS nested_child,
arrayJoin(JSONExtractArrayRaw(state, 'nestedList')) AS nested_list,
arrayJoin(JSONExtractArrayRaw(nested_list, 'list')) AS nested_list_list,
JSONExtractString(nested_list_list,'child') AS nested_list_list_child
SELECT
JSONExtract(state,'id', 'String') AS id,
JSONExtract(state,'string', 'String') AS string,
JSONExtract(state,'int', 'Int32') AS int,
JSONExtract(state,'long', 'Int64') AS long,
JSONExtract(state,'double', 'Float64') AS double,
JSONExtract(state,'float', 'Float32') AS float,
JSONExtract(state,'boolean', 'Bool') AS boolean,
JSONExtract(state,'byte', 'UInt8') AS byte,
JSONExtract(state,'short', 'UInt16') AS short,
JSONExtract(state,'char', 'UInt16') AS char,
JSONExtract(state,'duration', 'Decimal64(9)') AS duration,
JSONExtract(state,'kotlinDuration', 'Int64') AS kotlin_duration,
JSONExtract(state,'date', 'UInt64') AS date,
JSONExtract(state,'sqlDate', 'UInt64') AS sql_date,
JSONExtract(state,'uuid', 'UUID') AS uuid,
JSONExtract(state,'localDate', 'Array(UInt32)') AS local_date,
JSONExtract(state,'localDateTime', 'Array(UInt32)') AS local_date_time,
JSONExtract(state,'localTime', 'Array(UInt32)') AS local_time,
JSONExtract(state,'instant', 'Decimal64(9)') AS instant,
JSONExtract(state,'zonedDateTime', 'Decimal64(9)') AS zoned_date_time,
JSONExtract(state,'offsetDateTime', 'Decimal64(9)') AS offset_date_time,
JSONExtract(state,'offsetTime', 'Array(String)') AS offset_time,
JSONExtract(state,'yearMonth', 'Array(UInt16)') AS year_month,
JSONExtract(state,'monthDay', 'String') AS month_day,
JSONExtract(state,'period', 'String') AS period,
JSONExtract(state,'year', 'UInt32') AS year,
JSONExtract(state,'month', 'String') AS month,
JSONExtract(state,'dayOfWeek', 'String') AS day_of_week,
JSONExtract(state,'stringList', 'Array(String)') AS string_list,
JSONExtract(state,'intList', 'Array(Int32)') AS int_list,
JSONExtract(state,'map', 'Map(String, String)') AS map,
JSONExtract(item,'id', 'String') AS item_id,
JSONExtract(item,'name', 'String') AS item_name,
JSONExtract(nested,'id', 'String') AS nested_id,
JSONExtract(nested,'name', 'String') AS nested_name,
JSONExtract(nested_child,'id', 'String') AS nested_child_id,
JSONExtract(nested_child,'name', 'String') AS nested_child_name,
JSONExtract(nested_list,'id', 'String') AS nested_list_id,
JSONExtract(nested_list,'name', 'String') AS nested_list_name,
JSONExtract(nested_list_list,'id', 'String') AS nested_list_list_id,
JSONExtract(nested_list_list,'name', 'String') AS nested_list_list_name,
JSONExtract(nested_list_list_child,'id', 'String') AS nested_list_list_child_id,
JSONExtract(nested_list_list_child,'name', 'String') AS nested_list_list_child_name,
id AS __id,
aggregate_id AS __aggregate_id,
tenant_id AS __tenant_id,
command_id AS __command_id,
request_id AS __request_id,
version AS __version,
first_operator AS __first_operator,
first_event_time AS __first_event_time,
create_time AS __create_time,
deleted AS __deleted
FROM bi_db.bi_aggregate_state_last;
