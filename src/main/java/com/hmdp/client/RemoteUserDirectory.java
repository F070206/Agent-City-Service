package com.hmdp.client;

import com.hmdp.dto.UserDTO;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("micro-content")
@RequiredArgsConstructor
public class RemoteUserDirectory implements UserDirectory {
    private final UserFeignClient client;
    private final InternalServiceMetrics metrics;

    @Override
    public UserDTO findById(Long id) {
        return metrics.record("user", "find_by_id", () -> client.findById(id));
    }

    @Override
    public List<UserDTO> findByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = ids.stream().distinct().toList();
        Map<Long, UserDTO> byId = new HashMap<>();
        for (int from = 0; from < distinctIds.size(); from += UserDirectory.MAX_BATCH_SIZE) {
            List<Long> batch = distinctIds.subList(from,
                    Math.min(from + UserDirectory.MAX_BATCH_SIZE, distinctIds.size()));
            List<UserDTO> users = metrics.record("user", "find_by_ids", () -> client.findByIds(batch));
            if (users == null) {
                throw new IllegalStateException("User service returned an empty response");
            }
            users.forEach(user -> byId.put(user.getId(), user));
        }
        return ids.stream().map(byId::get).filter(user -> user != null).toList();
    }
}
