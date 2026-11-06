package com.hmdp.ai.mapper;

import com.hmdp.ai.entity.AiTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AiTaskMapper {
    @Insert("INSERT INTO tb_ai_task (message_id, conversation_id, user_id, content, x, y) "
            + "VALUES (#{messageId}, #{conversationId}, #{userId}, #{content}, #{x}, #{y})")
    int insertTask(AiTask task);

    @Select("SELECT candidate.messageId, candidate.conversationId, candidate.userId, candidate.content, "
            + "candidate.x, candidate.y, candidate.status, candidate.leaseUntil, candidate.attempts "
            + "FROM ((SELECT task.message_id AS messageId, task.conversation_id AS conversationId, "
            + "task.user_id AS userId, task.content, task.x, task.y, task.status, "
            + "task.lease_until AS leaseUntil, task.attempts FROM tb_ai_task task "
            + "WHERE task.status = 0 AND task.created_time <= "
            + "DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 30 SECOND) "
            + "AND NOT EXISTS (SELECT 1 FROM tb_ai_task earlier "
            + "WHERE earlier.conversation_id = task.conversation_id "
            + "AND earlier.message_id < task.message_id AND earlier.status <> 2) "
            + "ORDER BY task.message_id LIMIT 100) "
            + "UNION ALL "
            + "(SELECT task.message_id AS messageId, task.conversation_id AS conversationId, "
            + "task.user_id AS userId, task.content, task.x, task.y, task.status, "
            + "task.lease_until AS leaseUntil, task.attempts FROM tb_ai_task task "
            + "WHERE task.status = 1 AND task.lease_until < CURRENT_TIMESTAMP "
            + "AND NOT EXISTS (SELECT 1 FROM tb_ai_task earlier "
            + "WHERE earlier.conversation_id = task.conversation_id "
            + "AND earlier.message_id < task.message_id AND earlier.status <> 2) "
            + "ORDER BY task.message_id LIMIT 100)) candidate "
            + "ORDER BY candidate.messageId LIMIT 100")
    List<AiTask> findRecoverable();

    @Select("SELECT candidate.message_id AS messageId, candidate.conversation_id AS conversationId, "
            + "candidate.user_id AS userId, candidate.content, candidate.x, candidate.y "
            + "FROM tb_ai_task candidate WHERE candidate.conversation_id = #{conversationId} "
            + "AND candidate.status = 0 AND NOT EXISTS (SELECT 1 FROM tb_ai_task earlier "
            + "WHERE earlier.conversation_id = candidate.conversation_id "
            + "AND earlier.message_id < candidate.message_id AND earlier.status <> 2) "
            + "ORDER BY candidate.message_id LIMIT 1")
    AiTask findNextPending(@Param("conversationId") Long conversationId);

    @Update("UPDATE tb_ai_task current_task LEFT JOIN tb_ai_task earlier_task "
            + "ON earlier_task.conversation_id = current_task.conversation_id "
            + "AND earlier_task.message_id < current_task.message_id AND earlier_task.status <> 2 "
            + "SET current_task.status = 1, current_task.claim_token = #{token}, "
            + "current_task.attempts = current_task.attempts + 1, "
            + "current_task.lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 5 MINUTE) "
            + "WHERE current_task.message_id = #{messageId} AND earlier_task.message_id IS NULL "
            + "AND (current_task.status = 0 "
            + "OR (current_task.status = 1 AND current_task.lease_until < CURRENT_TIMESTAMP))")
    int claim(@Param("messageId") Long messageId, @Param("token") String token);

    @Update("UPDATE tb_ai_task SET lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 5 MINUTE) "
            + "WHERE message_id = #{messageId} AND status = 1 AND claim_token = #{token}")
    int renewLease(@Param("messageId") Long messageId, @Param("token") String token);

    @Update("UPDATE tb_ai_task SET status = 0, claim_token = NULL, lease_until = NULL "
            + "WHERE message_id = #{messageId} AND status = 1 AND claim_token = #{token}")
    int releaseClaim(@Param("messageId") Long messageId, @Param("token") String token);

    @Update("UPDATE tb_ai_task SET status = 2, lease_until = NULL "
            + "WHERE message_id = #{messageId} AND status = 1 AND claim_token = #{token}")
    int complete(@Param("messageId") Long messageId, @Param("token") String token);

    @Update("UPDATE tb_ai_task SET status = 2, lease_until = NULL "
            + "WHERE message_id = #{messageId} AND status = 0")
    int completeRejected(@Param("messageId") Long messageId);
}
