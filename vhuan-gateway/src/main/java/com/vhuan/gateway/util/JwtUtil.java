package com.vhuan.gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 解析工具
 * <p>
 * 仅用于 Gateway 侧校验并解析 Access Token，将用户身份信息透传给下游服务。
 * Token 由 vhuan-auth 服务负责签发，因此本类只做"解析"不做"生成"。
 * 使用 jjwt 0.12 新版 API（parser().verifyWith(...)）。
 * </p>
 */
@Component
public class JwtUtil {

    /** 签名密钥（与 vhuan-auth 签发时使用同一密钥，来自 application.yml 的 jwt.secret） */
    @Value("${jwt.secret}")
    private String secret;

    /** 由密钥派生出的 HMAC 签名密钥（HS256） */
    private SecretKey key;

    @PostConstruct
    public void init() {
        // HS256 要求密钥字节长度 >= 32，直接使用配置的 UTF-8 字节作为 HMAC 密钥
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析 JWT，返回 payload（Claims）。
     * 若 Token 无效或已过期，会抛出对应的 JwtException 子类，由调用方处理。
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return 解析后的 Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
