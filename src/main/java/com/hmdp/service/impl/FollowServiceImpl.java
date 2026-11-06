package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.client.UserDirectory;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserDirectory userDirectory;
    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (followUserId == null || followUserId <= 0 || followUserId.equals(userId) || isFollow == null) {
            return Result.fail("关注目标无效");
        }
        String key = "follows:" + userId;
        RLock lock = redissonClient.getLock("lock:follow:" + userId + ":" + followUserId);
        lock.lock();
        try {
            boolean alreadyFollowing = query().eq("user_id", userId)
                    .eq("follow_user_id", followUserId).count() > 0;
            if (isFollow) {
                if (!alreadyFollowing) {
                    Follow follow = new Follow();
                    follow.setUserId(userId);
                    follow.setFollowUserId(followUserId);
                    if (!save(follow)) {
                        return Result.fail("关注失败，请稍后重试");
                    }
                }
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            } else {
                if (alreadyFollowing) {
                    remove(new QueryWrapper<Follow>()
                            .eq("user_id", userId).eq("follow_user_id", followUserId));
                }
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
            return Result.ok();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> users = userDirectory.findByIds(ids);
        return Result.ok(users);
    }
}
