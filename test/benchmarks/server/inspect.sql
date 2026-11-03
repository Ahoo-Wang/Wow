-- The Wow benchmark server's store after a session (bench.sh inspect --sql): each table's size, and the events stored,
-- as one JSON value. The account's stream table has a row per command, its events in `events` ({name, revision,
-- payload}, the name qualified); the snapshot table a row per account.
analyze;
select json_build_object(
    'tables', (
        select json_agg(json_build_object(
            'name', c.relname,
            'kind', case c.relname
                when 'wow_log__transfer__account' then 'events'
                when 'wow_snapshot__transfer__account' then 'snapshots'
                else 'other' end,
            'rows', greatest(c.reltuples, 0)::bigint,
            'totalBytes', pg_total_relation_size(c.oid),
            'indexBytes', pg_indexes_size(c.oid)
        ) order by c.relname)
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where c.relkind = 'r' and n.nspname = 'public'
    ),
    'records', (select count(*) from wow_log__transfer__account),
    'snapshots', (select count(*) from wow_snapshot__transfer__account),
    'events', (
        select json_object_agg(name, stored)
        from (
            select regexp_replace(e ->> 'name', '^.*[.]', '') as name, count(*) as stored
            from wow_log__transfer__account, jsonb_array_elements(events) e
            group by 1
        ) named
    )
);
