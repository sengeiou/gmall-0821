package com.atguigu.gmall.ums.mapper;

import com.atguigu.gmall.ums.entity.UserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表
 * 
 * @author xiaoyaoma
 * @email 534368107@qq.com
 * @date 2021-02-23 19:13:03
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
	
}
