local prepareKey = KEYS[1];

local currentAt = tonumber(ARGV[1]);

local ttlAtField = "ttlAt";
local function getCurrentTtlAt()
    local currentTtlAt = redis.call("HGET", prepareKey, ttlAtField);
    if currentTtlAt then
        return tonumber(currentTtlAt);
    end
    return 0;
end

if getCurrentTtlAt() < currentAt then
    return false;
end

local result = redis.call("DEL", prepareKey);

if result > 0 then
    return true
end

return false
