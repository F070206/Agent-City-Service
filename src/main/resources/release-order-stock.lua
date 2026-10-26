-- KEYS: 库存、用户占用集合、订单回补标记、订单 pending；ARGV[1]: 用户 ID。
-- 标记不设 TTL：应覆盖数据库任务的整个重试周期，不能让旧任务释放用户的新订单。
if redis.call('exists', KEYS[3]) == 1 then
    return 0
end

-- 写入前检查数据。Redis 丢失数据时保留任务并报警，不能从 0 凭空构造库存。
local stock = redis.call('get', KEYS[1])
if not stock or not string.match(stock, '^%d+$') then
    return -1
end
if redis.call('sismember', KEYS[2], ARGV[1]) ~= 1 then
    return -2
end

redis.call('incrby', KEYS[1], 1)
redis.call('srem', KEYS[2], ARGV[1])
redis.call('set', KEYS[3], '1')
redis.call('del', KEYS[4])
return 1
