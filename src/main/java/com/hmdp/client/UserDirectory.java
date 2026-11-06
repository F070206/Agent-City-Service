package com.hmdp.client;

import com.hmdp.dto.UserDTO;
import java.util.List;

/** Read-only user data needed by the content service. */
public interface UserDirectory {
    int MAX_BATCH_SIZE = 500;

    UserDTO findById(Long id);

    List<UserDTO> findByIds(List<Long> ids);
}
