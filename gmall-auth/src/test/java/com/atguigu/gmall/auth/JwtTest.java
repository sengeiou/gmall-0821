package com.atguigu.gmall.auth;

import com.atguigu.gmall.common.utils.JwtUtils;
import com.atguigu.gmall.common.utils.RsaUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

public class JwtTest {

    // 别忘了创建D:\\project\rsa目录
private static final String pubKeyPath = "D:\\html\\rsa.pub";
    private static final String priKeyPath = "D:\\html\\rsa.pri";

    private PublicKey publicKey;

    private PrivateKey privateKey;

    @Test
    public void testRsa() throws Exception {
        RsaUtils.generateKey(pubKeyPath, priKeyPath, "234");
    }

    @BeforeEach
    public void testGetRsa() throws Exception {
        this.publicKey = RsaUtils.getPublicKey(pubKeyPath);
        this.privateKey = RsaUtils.getPrivateKey(priKeyPath);
    }

    @Test
    public void testGenerateToken() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("id", "11");
        map.put("username", "liuyan");
        // 生成token
        String token = JwtUtils.generateToken(map, privateKey, 2);
        System.out.println("token = " + token);
    }

    @Test
    public void testParseToken() throws Exception {
        String token = "eyJhbGciOiJSUzI1NiJ9.eyJpZCI6IjExIiwidXNlcm5hbWUiOiJsaXV5YW4iLCJleHAiOjE2MTQxNjkzNjl9.dXRJ0vfkF0frxEh1Y8RK6GUPU6e2az5Hk7YBSdPILl5Bn0EcaNR21IaWo2VkPpw4G73oxuvvekwndYuPdPbWoB5UfHMvWlnKTYseVrkeWMRX7CM4LJa1P1_ckYnSpumKV7o5Ds7xUw547B28YmmsePRZ4C8Ax-IsEyPvax6PjsiKYUC9G4HsYcT91IJsixH5J8H7EmHfsjngjyVi-I2XUaIlZU-4SDLqPUZmAbFGoUtWT81iKFLD2vQJJSX1kOi7lcY8mQiapvQEsjx6DUSO8Kjp8i28FnyzhcaoZMeU0YcNA4OXjWqrENmORsJej5Zqi0iJvqgFvYwCuoOv2juZzQ";

        // 解析token
        Map<String, Object> map = JwtUtils.getInfoFromToken(token, publicKey);
        System.out.println("id: " + map.get("id"));
        System.out.println("userName: " + map.get("username"));
    }
}