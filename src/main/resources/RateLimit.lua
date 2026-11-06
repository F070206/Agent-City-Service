-- Atomic Redis-time sliding window.
-- ARGV: window length (ms), allowed request count, key TTL (seconds).
local redisTime = redis.call('TIME')
local currentTime = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
local windowMillis = tonumber(ARGV[1])
local maxRequests = tonumber(ARGV[2])
local expireSeconds = tonumber(ARGV[3])
local windowStartTime = currentTime - windowMillis
local requestId = tostring(currentTime) .. ':' .. tostring(redis.call('ZCARD', KEYS[1]) + 1)

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', windowStartTime)
local count = tonumber(redis.call('ZCOUNT', KEYS[1], windowStartTime, currentTime)) or 0

if maxRequests > 0 and count >= maxRequests then
    return 1
end

redis.call('ZADD', KEYS[1], currentTime, requestId)
redis.call('EXPIRE', KEYS[1], math.max(expireSeconds, 60))
return 0
