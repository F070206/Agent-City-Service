package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.client.UserDirectory;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.BlogFeedDispatchMapper;
import com.hmdp.entity.BlogFeedDispatch;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.UserHolder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlogServiceImplTest {
    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void shopBlogPageFiltersByShopAndOrdersNewestFirst() {
        BlogMapper mapper = mock(BlogMapper.class);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(mapper).selectPage(any(Page.class), any());
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        service.queryBlogByShopId(12L, 2);

        org.mockito.ArgumentCaptor<Wrapper<Blog>> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.ArgumentCaptor<Page<Blog>> pageCaptor =
                org.mockito.ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(pageCaptor.capture(), queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("shop_id ="));
        assertTrue(((java.util.Map<?, ?>) ReflectionTestUtils.getField(queryCaptor.getValue(),
                "paramNameValuePairs")).containsValue(12L));
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("ORDER BY id DESC"));
        assertEquals(2, pageCaptor.getValue().getCurrent());
        assertEquals(10, pageCaptor.getValue().getSize());
    }

    @Test
    void saveBlogRejectsMissingShopBeforePersisting() {
        BlogMapper mapper = mock(BlogMapper.class);
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        UserDTO author = new UserDTO();
        author.setId(7L);
        UserHolder.saveUser(author);

        var result = service.saveBlog(new Blog().setTitle("标题").setImages("").setContent("正文"));

        assertFalse(result.getSuccess());
        verify(mapper, never()).insert(any(Blog.class));
    }

    @Test
    void saveBlogRejectsMissingReferencedShopBeforePersisting() {
        BlogMapper mapper = mock(BlogMapper.class);
        ShopMapper shops = mock(ShopMapper.class);
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "shopMapper", shops);
        when(shops.selectById(10L)).thenReturn(null);
        UserDTO author = new UserDTO();
        author.setId(7L);
        UserHolder.saveUser(author);

        var result = service.saveBlog(new Blog().setShopId(10L).setTitle("标题")
                .setImages("").setContent("正文"));

        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("店铺不存在"));
        verify(mapper, never()).insert(any(Blog.class));
    }

    @Test
    void saveBlogRejectsMoreThanNineImagesBeforePersisting() {
        BlogMapper mapper = mock(BlogMapper.class);
        ShopMapper shops = mock(ShopMapper.class);
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "shopMapper", shops);
        when(shops.selectById(10L)).thenReturn(new Shop().setId(10L));
        UserDTO author = new UserDTO();
        author.setId(7L);
        UserHolder.saveUser(author);
        String images = String.join(",", java.util.Collections.nCopies(10, "/blogs/image.png"));

        var result = service.saveBlog(new Blog().setShopId(10L).setTitle("标题")
                .setImages(images).setContent("正文"));

        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("最多9张"));
        verify(mapper, never()).insert(any(Blog.class));
    }

    @Test
    void saveBlogReadsFollowersByCompositeCursorAndWritesBoundedOutboxBatches() {
        BlogMapper mapper = mock(BlogMapper.class);
        Blog blog = new Blog().setShopId(10L).setTitle("标题").setImages("").setContent("正文");
        FollowMapper follows = mock(FollowMapper.class);
        List<Follow> firstPage = new java.util.ArrayList<>(500);
        for (long id = 1; id <= 499; id++) {
            firstPage.add(new Follow().setId(id).setUserId(id));
        }
        firstPage.add(new Follow().setId(500L).setUserId(499L));
        List<Follow> secondPage = List.of(new Follow().setId(501L).setUserId(499L));
        when(follows.selectFollowerPage(7L, 0L, 0L, 500)).thenReturn(firstPage);
        when(follows.selectFollowerPage(7L, 499L, 500L, 500)).thenReturn(secondPage);
        BlogFeedDispatchMapper dispatches = mock(BlogFeedDispatchMapper.class);
        BlogServiceImpl service = spy(new BlogServiceImpl());
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "followMapper", follows);
        ReflectionTestUtils.setField(service, "blogFeedDispatchMapper", dispatches);
        ShopMapper shops = mock(ShopMapper.class);
        when(shops.selectById(10L)).thenReturn(new Shop().setId(10L));
        ReflectionTestUtils.setField(service, "shopMapper", shops);
        UserDTO author = new UserDTO();
        author.setId(7L);
        UserHolder.saveUser(author);

        doAnswer(invocation -> {
            blog.setId(77L);
            return 1;
        }).when(mapper).insert(blog);
        service.saveBlog(blog);
        verify(follows).selectFollowerPage(7L, 0L, 0L, 500);
        verify(follows).selectFollowerPage(7L, 499L, 500L, 500);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<BlogFeedDispatch>> tasks = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(dispatches, times(2)).insertPending(tasks.capture());
        assertEquals(List.of(500, 1), tasks.getAllValues().stream().map(List::size).toList());
        assertEquals(499L, tasks.getAllValues().get(0).get(499).getFollowerId());
        assertEquals(499L, tasks.getAllValues().get(1).get(0).getFollowerId());
        assertTrue(tasks.getAllValues().stream().flatMap(List::stream)
                .allMatch(task -> task.getBlogId().equals(77L)));
    }

    @Test
    void detailLoadsAuthorThroughBatchDirectoryCall() {
        BlogMapper mapper = mock(BlogMapper.class);
        Blog blog = new Blog().setId(22L).setUserId(11L);
        when(mapper.selectById(22L)).thenReturn(blog);
        UserDirectory users = mock(UserDirectory.class);
        UserDTO author = new UserDTO();
        author.setId(11L);
        author.setNickName("甲");
        when(users.findByIds(List.of(11L))).thenReturn(List.of(author));
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userDirectory", users);

        service.queryBlogById(22L);

        verify(users).findByIds(List.of(11L));
        verify(users, never()).findById(any());
        assertEquals("甲", blog.getName());
    }

    @Test
    void hotPageLoadsAuthorsInOneDirectoryCall() {
        BlogMapper mapper = mock(BlogMapper.class);
        Blog first = new Blog().setUserId(11L);
        Blog second = new Blog().setUserId(12L);
        Blog repeatedAuthor = new Blog().setUserId(11L);
        Blog missingAuthor = new Blog().setUserId(13L);
        doAnswer(invocation -> {
            Page<Blog> page = invocation.getArgument(0);
            page.setRecords(List.of(first, second, repeatedAuthor, missingAuthor));
            return page;
        }).when(mapper).selectPage(any(Page.class), any());
        UserDirectory users = mock(UserDirectory.class);
        UserDTO firstAuthor = new UserDTO();
        firstAuthor.setId(11L);
        firstAuthor.setNickName("甲");
        UserDTO secondAuthor = new UserDTO();
        secondAuthor.setId(12L);
        secondAuthor.setNickName("乙");
        when(users.findByIds(List.of(11L, 12L, 13L))).thenReturn(List.of(
                firstAuthor, secondAuthor));
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userDirectory", users);

        service.queryHotBlog(1);

        org.mockito.ArgumentCaptor<Wrapper<Blog>> queryCaptor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectPage(any(Page.class), queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("ORDER BY liked DESC,id DESC"));
        verify(users).findByIds(List.of(11L, 12L, 13L));
        verify(users, never()).findById(any());
        assertEquals("甲", first.getName());
        assertEquals("乙", second.getName());
        assertEquals("甲", repeatedAuthor.getName());
        assertNull(missingAuthor.getName());
    }

    @Test
    void followFeedLoadsAuthorsInOneDirectoryCall() {
        BlogMapper mapper = mock(BlogMapper.class);
        Blog first = new Blog().setId(101L).setUserId(11L);
        Blog second = new Blog().setId(102L).setUserId(12L);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        UserDirectory users = mock(UserDirectory.class);
        UserDTO firstAuthor = new UserDTO();
        firstAuthor.setId(11L);
        firstAuthor.setNickName("甲");
        UserDTO secondAuthor = new UserDTO();
        secondAuthor.setId(12L);
        secondAuthor.setNickName("乙");
        when(users.findByIds(List.of(11L, 12L))).thenReturn(List.of(firstAuthor, secondAuthor));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(redis.executePipelined(any(SessionCallback.class))).thenReturn(java.util.Arrays.asList(1.0, null));
        ZSetOperations.TypedTuple<String> firstTuple = mock(ZSetOperations.TypedTuple.class);
        ZSetOperations.TypedTuple<String> secondTuple = mock(ZSetOperations.TypedTuple.class);
        when(firstTuple.getValue()).thenReturn("101");
        when(firstTuple.getScore()).thenReturn(2.0);
        when(secondTuple.getValue()).thenReturn("102");
        when(secondTuple.getScore()).thenReturn(1.0);
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(firstTuple, secondTuple));
        when(zsets.reverseRangeByScoreWithScores("feed:7", 0, 100L, 0, 2)).thenReturn(tuples);
        BlogServiceImpl service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userDirectory", users);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserDTO current = new UserDTO();
        current.setId(7L);
        UserHolder.saveUser(current);

        service.queryBlogOfFollow(100L, 0);

        verify(users).findByIds(List.of(11L, 12L));
        verify(users, never()).findById(any());
        assertEquals("甲", first.getName());
        assertEquals("乙", second.getName());
        assertTrue(first.getIsLike());
        assertFalse(second.getIsLike());

        @SuppressWarnings({ "rawtypes", "unchecked" })
        org.mockito.ArgumentCaptor<SessionCallback> pipelineCaptor =
                org.mockito.ArgumentCaptor.forClass(SessionCallback.class);
        verify(redis).executePipelined(pipelineCaptor.capture());
        @SuppressWarnings("unchecked")
        RedisOperations<String, String> pipelineOperations = mock(RedisOperations.class);
        ZSetOperations<String, String> pipelineZsets = mock(ZSetOperations.class);
        when(pipelineOperations.opsForZSet()).thenReturn(pipelineZsets);
        pipelineCaptor.getValue().execute(pipelineOperations);
        verify(pipelineZsets).score("blog:liked:101", "7");
        verify(pipelineZsets).score("blog:liked:102", "7");
    }
}
