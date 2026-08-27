local prepareKey = KEYS[1];

local currentAt = tonumber(ARGV[1]);
local ttlAt = ARGV[2];
local value = ARGV[3];

local ttlAtField = "ttlAt";
local valueField = "value";
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
