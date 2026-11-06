package com.hmdp.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BlogFeedDispatch {
    private Long blogId;
    private Long followerId;
    private LocalDateTime blogCreatedAt;
}
