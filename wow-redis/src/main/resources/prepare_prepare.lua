-- Note: starting with Redis 5, the replication method described in this section (scripts effects replication) is the default and does not need to be explicitly enabled.
redis.replicate_commands();

local key = KEYS[1];

local currentAt = tonumber(ARGV[1]);
local ttlAt = ARGV[2];
local value = ARGV[3];

local ttlAtField = "ttlAt";
local valueField = "value";
local prepareKey = "prepare:" .. key;

local function getCurrentTtlAt()
    local currentTtlAt = redis.call("HGET", prepareKey, ttlAtField);
    if currentTtlAt then
        return tonumber(currentTtlAt);
    end
    return 0;
end

if getCurrentTtlAt() > currentAt then
    return false;
end

redis.call("HSET", prepareKey, ttlAtField, ttlAt, valueField, value);

return true;