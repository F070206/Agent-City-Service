-- KEYS[1]: activity hash. ARGV: version, begin, end, voucher status, type.
local incomingVersion = tonumber(ARGV[1])
if not incomingVersion then
    return redis.error_reply('invalid activity version')
end

local currentVersion = tonumber(redis.call('hget', KEYS[1], 'version'))
if currentVersion and currentVersion > incomingVersion then
    return 0
end

redis.call('hset', KEYS[1],
    'version', ARGV[1],
    'beginTime', ARGV[2],
    'endTime', ARGV[3],
    'voucherStatus', ARGV[4],
    'voucherType', ARGV[5])
return 1
