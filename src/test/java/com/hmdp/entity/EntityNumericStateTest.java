package com.hmdp.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityNumericStateTest {
    @Test
    void membershipLevelRetainsItsNumericRangeInJson() throws Exception {
        assertEquals(Integer.class, UserInfo.class.getDeclaredField("level").getType());

        UserInfo info = new UserInfo();
        ReflectionTestUtils.setField(info, "level", 3);
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(info));

        assertTrue(json.path("level").isIntegralNumber());
        assertEquals(3, json.path("level").intValue());
    }

    @Test
    void commentStatusRetainsItsNumericRangeInJson() throws Exception {
        assertEquals(Integer.class, BlogComments.class.getDeclaredField("status").getType());

        BlogComments comment = new BlogComments();
        ReflectionTestUtils.setField(comment, "status", 2);
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(comment));

        assertTrue(json.path("status").isIntegralNumber());
        assertEquals(2, json.path("status").intValue());
    }
}
