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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     * 校验手机号，不符合返回错误
     * 符合生成验证码，保存验证码到redis
     * 发送验证码
     * 返回ok
     *
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 验证手机号，用的是工具类
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 符合，生成验证码，保存验证码到redis
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // TODO 发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);

        return Result.ok();
    }

    /**
     * 登录功能
     * 校验手机号，不符合返回错误
     * 符合，获取redis中的验证码
     * 进行验证码比对，不一致返回错误
     * 一致，根据手机号查询用户
     * 判断用户是否存在，不存在则创建
     * 保存用户信息到redis中
     * 生成token
     * 把user对象专为Hash存在redis中
     * 把用户token保存到前端中
     * 返回ok
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号，不符合返回错误
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // 符合，获取redis中的验证码
        Object cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        // 进行验证码比对，不一致返回错误
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            // 不一致，返回错误
            return Result.fail("验证码错误！");
        }
        // 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 判断用户是否存在，不存在则创建
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        // 生成token
        String token = UUID.randomUUID().toString(true);
        // 把user对象专为Hash存在redis中
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 把用户token保存到前端中
        return Result.ok(token);
    }

    /**
     * 创建用户
     * 根据手机号创建用户，id为user_随机生成
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

}
