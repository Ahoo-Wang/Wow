local snapshotKey = KEYS[1];
local snapshotVersion = tonumber(ARGV[1]);
local snapshot = ARGV[2];

local storedSnapshot = redis.call("GET", snapshotKey);
if storedSnapshot then
    local storedVersion = tonumber(cjson.decode(storedSnapshot)["version"]);
    if storedVersion == nil then
        error("Stored Wow snapshot has no numeric version.");
    end
    if storedVersion > snapshotVersion then
        return "Ignored";
    end
end

redis.call("SET", snapshotKey, snapshot);
return "Ok";
