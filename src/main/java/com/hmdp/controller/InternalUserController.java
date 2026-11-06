package com.hmdp.controller;

import com.hmdp.client.InternalApiGuard;
import com.hmdp.client.LocalUserDirectory;
import com.hmdp.client.UserDirectory;
import com.hmdp.dto.UserDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("micro-user")
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {
    private final LocalUserDirectory users;
    private final InternalApiGuard guard;

    @GetMapping("/{id}")
    public UserDTO get(@RequestHeader("X-Internal-Token") String token, @PathVariable Long id) {
        guard.verify(token);
        return users.findById(id);
    }

    @GetMapping
    public List<UserDTO> getMany(@RequestHeader("X-Internal-Token") String token,
            @RequestParam List<Long> ids) {
        guard.verify(token);
        if (ids.size() > UserDirectory.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "At most " + UserDirectory.MAX_BATCH_SIZE + " user IDs are allowed");
        }
        return users.findByIds(ids);
    }
}
