local eventStreamKey = KEYS[1];
local aggregateIdIndexKey = KEYS[2];
local requestIdxKey = KEYS[3];

local requestId = ARGV[1];
local version = tonumber(ARGV[2]);
local value = ARGV[3];
local aggregateIdIndexMember = ARGV[4];
local aggregateIdIndexMemberPrefix = ARGV[5];
local aggregateIdIndexMemberUpperBound = ARGV[6];

local count = redis.call("ZCARD", eventStreamKey)
if count ~= (version - 1) then
    return "EventVersionConflict";
end

if version == 1 then
    local existingAggregateIds = redis.call(
        "ZRANGEBYLEX",
        aggregateIdIndexKey,
        "[" .. aggregateIdIndexMemberPrefix,
        "(" .. aggregateIdIndexMemberUpperBound,
        "LIMIT",
        0,
        1
    );
    if #existingAggregateIds > 0 then
        return "DuplicateAggregateId";
    end
end

local added = redis.call("SADD", requestIdxKey, requestId)
if added == 0 then
    return "DuplicateRequestId";
end

redis.call("ZADD", eventStreamKey, version, value);
if version == 1 then
    redis.call("ZADD", aggregateIdIndexKey, 0, aggregateIdIndexMember);
end
return "Ok";
