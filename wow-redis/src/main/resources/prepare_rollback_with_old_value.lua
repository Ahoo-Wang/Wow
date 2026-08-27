local prepareKey = KEYS[1];

local oldValue = ARGV[1];

local valueField = "value";
local currentValue = redis.call("HGET", prepareKey, valueField);

if currentValue == nil or currentValue ~= oldValue then
    return false;
end

local result = redis.call("DEL", prepareKey);

if result > 0 then
    return true
end

return false
