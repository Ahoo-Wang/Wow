-- Note: starting with Redis 5, the replication method described in this section (scripts effects replication) is the default and does not need to be explicitly enabled.
redis.replicate_commands();

local aggregateIdKey = KEYS[1];

local contextAlias = ARGV[1];
local aggregateName = ARGV[2];
local requestId = ARGV[3];
local version = tonumber(ARGV[4]);
local value = ARGV[5];

local eventStreamKey = contextAlias .. ":" .. aggregateName .. ":event:" .. aggregateIdKey

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
return tostring(result)