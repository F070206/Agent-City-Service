package com.hmdp.client;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("!micro-content")
@RequiredArgsConstructor
public class LocalUserDirectory implements UserDirectory {
    private final IUserService userService;

    @Override
    public UserDTO findById(Long id) {
        var user = userService.getById(id);
        return user == null ? null : BeanUtil.copyProperties(user, UserDTO.class);
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
            userService.listByIds(batch).stream()
                    .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                    .forEach(user -> byId.put(user.getId(), user));
        }
        return ids.stream().map(byId::get).filter(user -> user != null).toList();
    }
}
