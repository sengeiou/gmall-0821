package com.atguigu.gmall.cart.service;

import com.alibaba.fastjson.JSON;
import com.atguigu.gmall.cart.feign.GmallPmsClient;
import com.atguigu.gmall.cart.feign.GmallSmsClient;
import com.atguigu.gmall.cart.feign.GmallWmsClient;
import com.atguigu.gmall.cart.interceptor.LoginInterceptor;
import com.atguigu.gmall.cart.mapper.CartMapper;
import com.atguigu.gmall.cart.pojo.Cart;
import com.atguigu.gmall.cart.pojo.UserInfo;
import com.atguigu.gmall.common.bean.ResponseVo;
import com.atguigu.gmall.common.exception.CartExecption;
import com.atguigu.gmall.pms.entity.SkuAttrValueEntity;
import com.atguigu.gmall.pms.entity.SkuEntity;
import com.atguigu.gmall.sms.vo.ItemSaleVo;
import com.atguigu.gmall.wms.entity.WareSkuEntity;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;


@Service
public class CartService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CartAsyncService asyncService;

    @Autowired
    private GmallPmsClient pmsClient;

    @Autowired
    private GmallSmsClient smsClient;

    @Autowired
    private GmallWmsClient wmsClient;

//    @Autowired
//    private ThreadPoolExecutor poolExecutor;


    private static final String KEY_PREFIX = "cart:info:";

    public void saveCart(Cart cart) {
        // 1、 获取用户的登录信息
        String userId = getUserId();

        // 2、获取当前用户的购物车 ，获取到内层的map
        BoundHashOperations<String, Object, Object> hashOps = this.redisTemplate.boundHashOps(KEY_PREFIX + userId);

        // 3、判断该用户的购物车是否包含该商品
        String skuId = cart.getSkuId().toString();
        BigDecimal count = cart.getCount();
        if (hashOps.hasKey(skuId)) {
            // 包含更新数量
            String cartJson = hashOps.get(skuId).toString();
            cart = JSON.parseObject(cartJson, Cart.class);
            cart.setCount(cart.getCount().add(count));

            // 用更新后的购物车对象覆盖掉redis中的对象
            this.asyncService.updateCart(userId, skuId, cart);

//            this.cartMapper.update(cart, new UpdateWrapper<Cart>().eq("user_id", userId).eq("sku_id", skuId));

        } else {
            // 不包含：新增一条记录
            cart.setUserId(userId);
            cart.setCheck(true);

            // 查询sku相关信息
            ResponseVo<SkuEntity> skuEntityResponseVo = this.pmsClient.querySkuById(cart.getSkuId());
            SkuEntity skuEntity = skuEntityResponseVo.getData();
            if (skuEntity == null) {
                return;
            }
            cart.setTitle(skuEntity.getTitle());
            cart.setPrice(skuEntity.getPrice());
            cart.setDefaultImage(skuEntity.getDefaultImage());

            // 查询库存
            ResponseVo<List<WareSkuEntity>> listResponseVo = this.wmsClient.queryWareSkusBySkuId(cart.getSkuId());
            List<WareSkuEntity> wareSkuEntities = listResponseVo.getData();
            if (!CollectionUtils.isEmpty(wareSkuEntities)) {
                cart.setStore(wareSkuEntities.stream().anyMatch(wareSkuEntity -> wareSkuEntity.getStock() - wareSkuEntity.getStockLocked() > 0));
            }

            // 销售属性
            ResponseVo<List<SkuAttrValueEntity>> saleAttrResponseVo = this.pmsClient.querySaleAttrValuesBySkuId(cart.getSkuId());
            List<SkuAttrValueEntity> skuAttrValueEntities = saleAttrResponseVo.getData();
            cart.setSaleAttrs(JSON.toJSONString(skuAttrValueEntities));
            // 查询营销信息
            ResponseVo<List<ItemSaleVo>> salesResponseVo = this.smsClient.querySalesBySkuId(cart.getSkuId());
            List<ItemSaleVo> itemSaleVos = salesResponseVo.getData();
            cart.setSales(JSON.toJSONString(itemSaleVos));
//            hashOps.put(skuId, JSON.toJSONString(cart));

            this.asyncService.insertCart(cart);
        }
        // 用更新后的购物车对象覆盖掉redis中的对象
        hashOps.put(skuId, JSON.toJSONString(cart));
    }


    private String getUserId() {
        UserInfo userInfo = LoginInterceptor.getUserInfo();
        if (userInfo.getUserId() == null) {
            return userInfo.getUserKey();
        } else {
            return userInfo.getUserId().toString();
        }
    }

    public Cart queryCartBySkuId(Long skuId) {

        String userId = this.getUserId();

        // 根据外层key（userId、userKey） 获取内层map
        BoundHashOperations<String, Object, Object> hashOps = this.redisTemplate.boundHashOps(KEY_PREFIX + userId);

        if (hashOps.hasKey(skuId.toString())) {
            String cartJson = hashOps.get(skuId.toString()).toString();
            return JSON.parseObject(cartJson, Cart.class);
        }
        throw new CartExecption("该用户的购物车不包含该记录...");
    }

    public List<Cart> queryCarts() {

        // 1.获取userKey
        UserInfo userInfo = LoginInterceptor.getUserInfo();
        String userKey = userInfo.getUserKey();
        // 组装key
        String unloginKey = KEY_PREFIX + userKey;
        // 2.根据userKey查询未登录的购物车
        // 获取未登陆购物车的内层的map
        BoundHashOperations<String, Object, Object> hashOps = this.redisTemplate.boundHashOps(unloginKey);
        // 获取未登录用户购物车的所有记录List<String>
        List<Object> unloginCartJsons = hashOps.values();
        List<Cart> unloginCart = null;
        if (!CollectionUtils.isEmpty(unloginCartJsons)) {
            unloginCart = unloginCartJsons.stream().map(cartJson -> JSON.parseObject(cartJson.toString(), Cart.class)).collect(Collectors.toList());
        }

        // 获取userId
        Long userId = userInfo.getUserId();
        if (userId == null) {
            return unloginCart;
        }
        // 5.把未登录的购物车合并到登录状态的购物车
        String loginKey = KEY_PREFIX + userId;
        BoundHashOperations<String, Object, Object> loginHashOps = this.redisTemplate.boundHashOps(loginKey);
        if (!CollectionUtils.isEmpty(unloginCart)) {
            unloginCart.forEach(cart -> { // 未登录状态的购物车记录
                String skuId = cart.getSkuId().toString();
                BigDecimal count = cart.getCount();
                if (loginHashOps.hasKey(skuId)) {
                    // 用户的购物车包含了该记录 合并数量
                    String cartJson = loginHashOps.get(skuId).toString(); // 获取登录购物车的对应记录
                    cart = JSON.parseObject(cartJson, Cart.class);
                    cart.setCount(cart.getCount().add(count));
                    // 写入redis 异步写入mysql
                    this.asyncService.updateCart(userId.toString(), skuId, cart);
//                    this.cartMapper.update(cart, new UpdateWrapper<Cart>().eq("user_id", userId.toString()).eq("sku_id", skuId));
                } else {
                    // 用户购物车不包含该记录 新增记录
                    cart.setUserId(userId.toString());
                    this.asyncService.insertCart(cart);
                }
                loginHashOps.put(skuId, JSON.toJSONString(cart));
            });
        }
        // 6.把未登录的购物车删除
        this.redisTemplate.delete(unloginKey);
        this.asyncService.deleteCart(userKey);


        // 7.返回登录状态的购物车
        List<Object> cartJsons = loginHashOps.values();
        if (!CollectionUtils.isEmpty(cartJsons)) {
            // 将cartJson 反序列为 Cart对象 并收集成集合
            return cartJsons.stream().map(cartJson -> JSON.parseObject(cartJson.toString(), Cart.class)).collect(Collectors.toList());
        }
        return null;
    }

    public void updayeNum(Cart cart) {

        String userId = this.getUserId();
        BoundHashOperations<String, Object, Object> hashOps = this.redisTemplate.boundHashOps(KEY_PREFIX + userId);
        if (!hashOps.hasKey(cart.getSkuId().toString())) {
            throw new CartExecption("该用户对应的购物车数据不存在");
        }
        BigDecimal count = cart.getCount();
        String cartJson = hashOps.get(cart.getSkuId().toString()).toString();
        cart = JSON.parseObject(cartJson, Cart.class);
        cart.setCount(count);

        hashOps.put(cart.getSkuId().toString(), JSON.toJSONString(cart));
        this.asyncService.updateCart(userId, cart.getUserId().toString(), cart);

    }
}
