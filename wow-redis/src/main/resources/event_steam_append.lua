-- Note: starting with Redis 5, the replication method described in this section (scripts effects replication) is the default and does not need to be explicitly enabled.
redis.replicate_commands();

local eventStreamKey = KEYS[1];
local aggregateIdIndexKey = KEYS[2];
local aggregateTenantIndexKey = KEYS[3];

local requestId = ARGV[1];
local version = tonumber(ARGV[2]);
local value = ARGV[3];
local aggregateId = ARGV[4];
local tenantId = ARGV[5];

local count = redis.call("ZCARD", eventStreamKey)
if count ~= (version - 1) then
    return "EventVersionConflict";
end

local requestIdxKey = eventStreamKey .. ":req_idx"
local added = redis.call("SADD", requestIdxKey, requestId)
if added == 0 then
    return "DuplicateRequestId";
end

local result = redis.call("ZADD", eventStreamKey, version, value);
redis.call("HSET", aggregateTenantIndexKey, aggregateId, tenantId);
redis.call("ZADD", aggregateIdIndexKey, 0, aggregateId);
return tostring(result)
