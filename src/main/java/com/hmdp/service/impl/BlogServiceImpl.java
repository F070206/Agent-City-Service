package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.client.UserDirectory;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.BlogFeedDispatchMapper;
import com.hmdp.entity.BlogFeedDispatch;
import com.hmdp.entity.Follow;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Override
    public boolean ownsImage(Long userId, String filename) {
        if (userId == null || filename == null) {
            return false;
        }
        return lambdaQuery()
                .eq(Blog::getUserId, userId)
                .like(Blog::getImages, filename)
                .list()
                .stream()
                .map(Blog::getImages)
                .filter(Objects::nonNull)
                .flatMap(images -> Arrays.stream(images.split(",")))
                .map(String::trim)
                .anyMatch(filename::equals);
    }

    @Resource
    private UserDirectory userDirectory;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private FollowMapper followMapper;
    @Resource
    private BlogFeedDispatchMapper blogFeedDispatchMapper;
    @Resource
    private ShopMapper shopMapper;
    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        queryBlogUsers(records);
        queryBlogLikes(records);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogByShopId(Long shopId, Integer current) {
        Page<Blog> page = query()
                .eq("shop_id", shopId)
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        queryBlogUsers(records);
        queryBlogLikes(records);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        // 3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogLikes(List<Blog> blogs) {
        if (blogs.isEmpty()) {
            return;
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        String userId = user.getId().toString();
        List<Object> scores = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                ZSetOperations<String, String> zSets =
                        (ZSetOperations<String, String>) operations.opsForZSet();
                for (Blog blog : blogs) {
                    zSets.score(BLOG_LIKED_KEY + blog.getId(), userId);
                }
                return null;
            }
        });
        if (scores == null || scores.size() != blogs.size()) {
            throw new IllegalStateException("Unexpected number of blog like lookup results");
        }
        for (int i = 0; i < blogs.size(); i++) {
            Object score = scores.get(i);
            if (score instanceof RuntimeException failure) {
                throw failure;
            }
            blogs.get(i).setIsLike(score != null);
        }
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String lockKey = "lock:blog:like:" + id + ":" + userId;
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try {
            String key = BLOG_LIKED_KEY + id;
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            if (score == null) {
                boolean isSuccess = update().setSql("liked = COALESCE(liked, 0) + 1").eq("id", id).update();
                if (isSuccess) {
                    stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                }
            } else {
                boolean isSuccess = update().setSql("liked = CASE WHEN liked IS NULL OR liked = 0 "
                        + "THEN 0 ELSE liked - 1 END").eq("id", id).update();
                if (isSuccess) {
                    stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                }
            }
            return Result.ok();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userDirectory.findByIds(ids);
        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    @Transactional
    public Result saveBlog(Blog blog) {
        if (blog == null || blog.getShopId() == null || blog.getShopId() <= 0) {
            return Result.fail("请选择有效店铺后再发布笔记");
        }
        if (shopMapper.selectById(blog.getShopId()) == null) {
            return Result.fail("关联店铺不存在");
        }
        String title = blog.getTitle();
        String content = blog.getContent();
        String images = blog.getImages();
        if (!org.springframework.util.StringUtils.hasText(title) || title.length() > 255) {
            return Result.fail("笔记标题不能为空且不能超过255个字符");
        }
        if (!org.springframework.util.StringUtils.hasText(content) || content.length() > 2048) {
            return Result.fail("笔记内容不能为空且不能超过2048个字符");
        }
        if (images == null || images.length() > 2048 || imageCount(images) > 9) {
            return Result.fail("笔记图片最多9张且地址总长度不能超过2048个字符");
        }
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        // 4.按复合游标分页扫描粉丝，限制单批内存，同时把投递任务写进同一事务。
        Long afterUserId = 0L;
        Long afterFollowId = 0L;
        List<Follow> followerPage;
        do {
            followerPage = followMapper.selectFollowerPage(user.getId(), afterUserId, afterFollowId, 500);
            if (followerPage == null || followerPage.isEmpty()) {
                break;
            }
            List<BlogFeedDispatch> tasks = followerPage.stream().map(follow -> {
                BlogFeedDispatch task = new BlogFeedDispatch();
                task.setBlogId(blog.getId());
                task.setFollowerId(follow.getUserId());
                return task;
            }).toList();
            blogFeedDispatchMapper.insertPending(tasks);
            Follow last = followerPage.get(followerPage.size() - 1);
            afterUserId = last.getUserId();
            afterFollowId = last.getId();
        } while (followerPage.size() == 500);
        // 5.返回id
        return Result.ok(blog.getId());
    }

    private int imageCount(String images) {
        if (images.isBlank()) {
            return 0;
        }
        return (int) Arrays.stream(images.split(",")).map(String::trim).filter(s -> !s.isEmpty()).count();
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        queryBlogUsers(blogs);
        queryBlogLikes(blogs);

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        queryBlogUsers(List.of(blog));
    }

    private void queryBlogUsers(List<Blog> blogs) {
        if (blogs.isEmpty()) {
            return;
        }
        List<Long> userIds = blogs.stream().map(Blog::getUserId).distinct().toList();
        Map<Long, UserDTO> users = userDirectory.findByIds(userIds).stream()
                .collect(Collectors.toMap(UserDTO::getId, user -> user));
        for (Blog blog : blogs) {
            UserDTO user = users.get(blog.getUserId());
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
        }
    }
}
