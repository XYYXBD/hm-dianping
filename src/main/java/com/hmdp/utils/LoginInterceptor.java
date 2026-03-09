package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 专门的登录拦截器
     * UserHolder是否有用户信息
     * 没有就直接拦截，返回错误状态码
     * 放行
     *
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断ThreadLocal中是否有用户信息
        if (UserHolder.getUser() == null) {
            // 没有，说明没有登录，直接拦截，返回错误状态码
            response.setStatus(401);
            return false;
        }
        return true;
    }

    /**
     * 移出用户
     * 在目标方法执行之后，移除用户信息
     * @param request
     * @param response
     * @param handler
     * @param ex
     * @throws Exception
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
