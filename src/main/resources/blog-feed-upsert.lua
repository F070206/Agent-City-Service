redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
local limit = tonumber(ARGV[3])
redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -(limit + 1))
return redis.call('ZCARD', KEYS[1])
