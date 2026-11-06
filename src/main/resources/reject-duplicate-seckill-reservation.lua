-- KEYS: stock, user set, per-order refund marker, per-order pending marker, user/order map;
-- ARGV: duplicate user ID, current database order ID.
-- The pending marker proves this order consumed Redis stock. The refund marker makes retries idempotent.
if redis.call('exists', KEYS[3]) == 1 then
    return 0
end

local pendingType = redis.call('type', KEYS[4]).ok
if pendingType ~= 'string' or redis.call('get', KEYS[4]) ~= ARGV[1] then
    return -1
end

local stockType = redis.call('type', KEYS[1]).ok
local stock = redis.call('get', KEYS[1])
if stockType ~= 'string' or not stock or not string.match(stock, '^%d+$') then
    return -2
end

local orderType = redis.call('type', KEYS[2]).ok
if orderType ~= 'none' and orderType ~= 'set' then
    return -3
end
local reservationType = redis.call('type', KEYS[5]).ok
if reservationType ~= 'none' and reservationType ~= 'hash' then
    return -4
end
if not ARGV[2] or ARGV[2] == '' then
    return -5
end

redis.call('incrby', KEYS[1], 1)
redis.call('sadd', KEYS[2], ARGV[1])
redis.call('hset', KEYS[5], ARGV[1], ARGV[2])
redis.call('set', KEYS[3], '1')
return 1
