local key = KEYS[1];
local requestId = ARGV[1];
local score = ARGV[2];
local value = ARGV[3];

local version = tonumber(score)

local count = redis.call("ZCARD", key)
if count ~= (version - 1) then
    return "EventVersionConflict";
end
-- TODO requestId


local result = redis.call("ZADD", key, score, value);
return tostring(result)