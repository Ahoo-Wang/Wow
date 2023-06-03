-- Note: starting with Redis 5, the replication method described in this section (scripts effects replication) is the default and does not need to be explicitly enabled.
redis.replicate_commands();

local key = KEYS[1];

local oldValue = ARGV[1];

local valueField = "value";
local prepareKey = "prepare:" .. key;

local currentValue = redis.call("HGET", prepareKey, valueField);

if currentValue == nil or currentValue ~= oldValue then
    return false;
end

local result = redis.call("DEL", prepareKey);

if result > 0 then
    return true
end

return false