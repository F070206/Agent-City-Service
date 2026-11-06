package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final DefaultRedisScript<Long> CONSUME_LOGIN_CODE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) end return 0", Long.class);
    private static final DefaultRedisScript<Long> SAVE_LOGIN_SESSION = new DefaultRedisScript<>(
            "for i = 1, #ARGV - 1, 2 do redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1]) end "
                    + "return redis.call('PEXPIRE', KEYS[1], ARGV[#ARGV])", Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${cityai.auth.mock-code-enabled:false}")
    private boolean mockCodeEnabled;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        String code = RandomUtil.randomNumbers(6);
        String redisKey = LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(redisKey, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (!code.equals(cachedCode)) {
            log.error("验证码写入Redis失败，phone={}", maskPhone(phone));
            return Result.fail("验证码发送失败，请稍后重试");
        }

        Long expireSeconds = stringRedisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        log.info("验证码发送成功，phone={}, ttlSeconds={}", maskPhone(phone), expireSeconds);
        return mockCodeEnabled ? Result.ok(code) : Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        String code = loginForm.getCode();
        Long consumed = stringRedisTemplate.execute(CONSUME_LOGIN_CODE,
                Collections.singletonList(LOGIN_CODE_KEY + phone), code);
        if (!Long.valueOf(1).equals(consumed)) {
            return Result.fail("验证码错误");
        }

        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        saveLoginSession(token, userMap);

        return Result.ok(token);
    }

    void saveLoginSession(String token, Map<String, Object> userMap) {
        List<String> arguments = new ArrayList<>(userMap.size() * 2 + 1);
        userMap.forEach((field, value) -> {
            arguments.add(field);
            arguments.add(String.valueOf(value));
        });
        arguments.add(Long.toString(TimeUnit.MINUTES.toMillis(LOGIN_USER_TTL)));
        Long saved = stringRedisTemplate.execute(SAVE_LOGIN_SESSION,
                Collections.singletonList(LOGIN_USER_KEY + token), arguments.toArray());
        if (!Long.valueOf(1L).equals(saved)) {
            throw new IllegalStateException("登录凭据写入或过期时间设置失败");
        }
    }

    @Override
    public Result logout(String token) {
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        return Result.ok();
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        if (!save(user) || user.getId() == null) {
            throw new IllegalStateException("创建用户失败，请稍后重试");
        }
        return user;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
