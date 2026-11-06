-- KEYS[1]: 秒杀库存；KEYS[2]: 用户占用集合；ARGV[1]: 用户 ID。
if redis.call('sismember', KEYS[2], ARGV[1]) ~= 1 then
    return 0
end
local stock = redis.call('get', KEYS[1])
if not stock or not string.match(stock, '^%d+$') then
    return -1
end
redis.call('incrby', KEYS[1], 1)
redis.call('srem', KEYS[2], ARGV[1])
return 1
