package com.hmdp.mapper;

import com.hmdp.entity.BlogFeedDispatch;
import com.hmdp.entity.BlogFeedDispatchBacklog;
import com.hmdp.micro.MapperRole;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@MapperRole.Content
public interface BlogFeedDispatchMapper {
    @Delete("DELETE FROM tb_blog_feed_dispatch WHERE delivered = 1 "
            + "ORDER BY updated_at, blog_id, follower_id LIMIT 500")
    int purgeDeliveredBatch();

    @Insert({"<script>", "INSERT INTO tb_blog_feed_dispatch (blog_id, follower_id) VALUES",
            "<foreach collection='tasks' item='task' separator=','>",
            "(#{task.blogId}, #{task.followerId})", "</foreach>",
            "ON DUPLICATE KEY UPDATE blog_id = VALUES(blog_id)", "</script>"})
    int insertPending(@Param("tasks") List<BlogFeedDispatch> tasks);

    @Update("UPDATE tb_blog_feed_dispatch SET claim_token = #{token}, "
            + "claim_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 2 MINUTE), "
            + "updated_at = CURRENT_TIMESTAMP WHERE delivered = 0 "
            + "AND next_retry_time <= CURRENT_TIMESTAMP "
            + "AND (claim_until IS NULL OR claim_until <= CURRENT_TIMESTAMP) "
            + "ORDER BY next_retry_time, blog_id, follower_id LIMIT 500")
    int claim(@Param("token") String token);

    @Select("SELECT dispatch.blog_id AS blogId, dispatch.follower_id AS followerId, "
            + "blog.create_time AS blogCreatedAt FROM tb_blog_feed_dispatch dispatch "
            + "JOIN tb_blog blog ON blog.id = dispatch.blog_id "
            + "WHERE dispatch.delivered = 0 AND dispatch.claim_token = #{token} "
            + "ORDER BY dispatch.blog_id, dispatch.follower_id")
    List<BlogFeedDispatch> findClaimed(@Param("token") String token);

    @Delete({"<script>", "DELETE FROM tb_blog_feed_dispatch WHERE delivered = 0 AND claim_token = #{token} "
            + "AND (blog_id, follower_id) IN",
            "<foreach collection='tasks' item='task' open='(' separator=',' close=')'>",
            "(#{task.blogId}, #{task.followerId})", "</foreach>", "</script>"})
    int deleteDeliveredBatch(@Param("tasks") List<BlogFeedDispatch> tasks, @Param("token") String token);

    @Update({"<script>", "UPDATE tb_blog_feed_dispatch SET attempts = attempts + 1, claim_token = NULL, "
            + "claim_until = NULL, next_retry_time = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 30 SECOND), "
            + "updated_at = CURRENT_TIMESTAMP WHERE delivered = 0 AND claim_token = #{token} "
            + "AND (blog_id, follower_id) IN",
            "<foreach collection='tasks' item='task' open='(' separator=',' close=')'>",
            "(#{task.blogId}, #{task.followerId})", "</foreach>", "</script>"})
    int recordFailures(@Param("tasks") List<BlogFeedDispatch> tasks, @Param("token") String token);

    @Select("SELECT COUNT(*) AS pendingCount, "
            + "COALESCE(MAX(TIMESTAMPDIFF(SECOND, created_at, CURRENT_TIMESTAMP)), 0) "
            + "AS oldestPendingAgeSeconds, "
            + "COALESCE(SUM(CASE WHEN attempts >= #{failureThreshold} THEN 1 ELSE 0 END), 0) "
            + "AS repeatedFailureCount FROM tb_blog_feed_dispatch WHERE delivered = 0")
    BlogFeedDispatchBacklog getBacklogSnapshot(@Param("failureThreshold") int failureThreshold);
}
