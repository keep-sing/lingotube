package com.moncoder.lingo.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.moncoder.lingo.entity.UmsUserLoginLog;
import com.moncoder.lingo.mapper.UmsUserLoginLogMapper;
import com.moncoder.lingo.user.service.IUserLoginLogService;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author moncoder
 * @since 2024-03-20 16:27:25
 */
@Service
public class UserLoginLogServiceImpl extends ServiceImpl<UmsUserLoginLogMapper, UmsUserLoginLog> implements IUserLoginLogService {

}
