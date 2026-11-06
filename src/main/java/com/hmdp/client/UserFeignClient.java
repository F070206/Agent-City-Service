package com.hmdp.client;

import com.hmdp.dto.UserDTO;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "cityai-user")
public interface UserFeignClient {
    @GetMapping("/internal/users/{id}")
    UserDTO findById(@PathVariable("id") Long id);

    @GetMapping("/internal/users")
    List<UserDTO> findByIds(@RequestParam("ids") List<Long> ids);
}
