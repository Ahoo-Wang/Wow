local prepareKey = KEYS[1];

local ttlAt = ARGV[1];
local value = ARGV[2];

local ttlAtField = "ttlAt";
local valueField = "value";
local currentTtlAt = redis.call("HGET", prepareKey, ttlAtField);

if currentTtlAt == nil then
    return false;
end

redis.call("HSET", prepareKey, ttlAtField, ttlAt, valueField, value);

return true;
