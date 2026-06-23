package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RegexUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession httpSession) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //2. 不符合
            return Result.fail("手机号格式错误！");
        }
        // 3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到.redis  有效期 2分钟
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码，模拟实现
        log.debug("发送短信验证码：{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //验证码登录
        if (StrUtil.isNotEmpty(loginForm.getCode())){
            // redis 验证码登录
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            String code = loginForm.getCode();
            if (cacheCode == null || !cacheCode.toString().equals(code)) {
                // 不一致，报错
                return Result.fail("验证码错误");
            }
            //查询用户是否存在
            User user = this.lambdaQuery().eq(User::getPhone, phone).one();
            if (user == null){
                user = createUserWithPhone(phone);
            }
            //保存用户信息到redis
            String token = saveUserRedis(user);


            return Result.ok(token);
        }
        //密码登录
        else if (StrUtil.isNotEmpty(loginForm.getPassword())){
            User user = this.lambdaQuery().eq(User::getPhone, phone).one();
            if (user == null ){
                return Result.fail("该用户还未注册！");
            }
            else  if (user != null && PasswordEncoder.matches(user.getPassword(),loginForm.getPassword())){
                String token = saveUserRedis(user);
                return Result.ok(token);
            }
            else {
                return Result.fail("密码错误！");
            }
        }
        else {
            return Result.fail("请输入验证码或者密码！");
        }
    }

    /**
     * 首次验证码登录，注册用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        this.save(user);
        return user;
    }


    private String saveUserRedis(User user){
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //讲对象转换为map结构
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true) //忽略空值字段
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;

        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置过期时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return token;
    }
}
