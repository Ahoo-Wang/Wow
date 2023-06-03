-- Note: starting with Redis 5, the replication method described in this section (scripts effects replication) is the default and does not need to be explicitly enabled.
redis.replicate_commands();

local key = KEYS[1];

local ttlAt = ARGV[1];
local value = ARGV[2];
local oldValue = ARGV[3];

local ttlAtField = "ttlAt";
local valueField = "value";
local prepareKey = "prepare:" .. key;

local currentValue = redis.call("HGET", prepareKey, valueField);

if currentValue ~= oldValue then
    return false;
end

redis.call("HSET", prepareKey, ttlAtField, ttlAt, valueField, value);

return true;