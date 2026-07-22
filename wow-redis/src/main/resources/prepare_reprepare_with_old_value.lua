local prepareKey = KEYS[1];

local ttlAt = ARGV[1];
local value = ARGV[2];
local oldValue = ARGV[3];

local ttlAtField = "ttlAt";
local valueField = "value";
local currentValue = redis.call("HGET", prepareKey, valueField);

if currentValue ~= oldValue then
    return false;
end

redis.call("HSET", prepareKey, ttlAtField, ttlAt, valueField, value);

return true;
