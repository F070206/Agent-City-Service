-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
local orderId = ARGV[3]
local payload = ARGV[4]
if not orderId or orderId == '' or not payload or payload == '' then
    return 6
end

local activityKey = 'seckill:activity:' .. voucherId
local beginTime = tonumber(redis.call('hget', activityKey, 'beginTime'))
local endTime = tonumber(redis.call('hget', activityKey, 'endTime'))
local voucherStatus = tonumber(redis.call('hget', activityKey, 'voucherStatus'))
local voucherType = tonumber(redis.call('hget', activityKey, 'voucherType'))
if not beginTime or not endTime or not voucherStatus or not voucherType then
    -- Activity metadata has not been warmed into Redis yet.
    return 3
end
if voucherStatus ~= 1 or voucherType ~= 1 then
    return 7
end

-- 与库存预扣在同一脚本中判断时间，避免请求跨过结束时刻后仍占用库存。
local serverTime = redis.call('time')
local now = tonumber(serverTime[1]) * 1000 + math.floor(tonumber(serverTime[2]) / 1000)
if now < beginTime then
    return 4
end
if now >= endTime then
    return 5
end

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId
local reservationKey = 'seckill:order:reservation:' .. voucherId
local recoveryKey = 'seckill:dispatch:recovery'

-- Lua 脚本中的运行时错误不会回滚此前写入；先确认最后要写入的共享恢复队列类型正确。
local recoveryType = redis.call('type', recoveryKey).ok
if recoveryType ~= 'none' and recoveryType ~= 'zset' then
    return 8
end
local orderType = redis.call('type', orderKey).ok
local reservationType = redis.call('type', reservationKey).ok
if (orderType ~= 'none' and orderType ~= 'set')
        or (reservationType ~= 'none' and reservationType ~= 'hash') then
    return 8
end

-- 3.脚本业务
-- 3.1.判断库存是否已初始化
local stock = tonumber(redis.call('get', stockKey))
if(not stock) then
    -- 3.1.1.库存尚未预热到Redis，返回3交给后端提示
    return 3
end

-- 3.2.判断库存是否充足
if(stock <= 0) then
    -- 3.2.1.库存不足，返回1
    return 1
end

-- 3.3.判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.1.存在，说明是重复下单，返回2
    return 2
end

-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.下单（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
redis.call('hset', reservationKey, userId, orderId)
-- 同一原子操作内保留待投递消息，进程在写入数据库任务前退出时可由后台恢复。
redis.call('set', 'seckill:order:pending:' .. orderId, userId)
redis.call('set', 'seckill:dispatch:payload:' .. orderId, payload)
redis.call('zadd', recoveryKey, 0, orderId)
return 0
