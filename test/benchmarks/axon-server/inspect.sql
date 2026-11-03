-- The Axon server's store after a session (bench.sh inspect --sql): each table's size, and the events stored, as one
-- JSON value. Axon's JDBC engine stores a row per event in `domainevententry` (`payloadtype` the event's class) and a
-- row per snapshot in `snapshotevententry`.
analyze;
select json_build_object(
    'tables', (
        select json_agg(json_build_object(
            'name', c.relname,
            'kind', case c.relname
                when 'domainevententry' then 'events'
                when 'snapshotevententry' then 'snapshots'
                else 'other' end,
            'rows', greatest(c.reltuples, 0)::bigint,
            'totalBytes', pg_total_relation_size(c.oid),
            'indexBytes', pg_indexes_size(c.oid)
        ) order by c.relname)
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where c.relkind = 'r' and n.nspname = 'public'
    ),
    'records', (select count(*) from domainevententry),
    'snapshots', (select count(*) from snapshotevententry),
    'events', (
        select json_object_agg(name, stored)
        from (
            select regexp_replace(payloadtype, '^.*[.]', '') as name, count(*) as stored
            from domainevententry
            group by 1
        ) named
    )
);
