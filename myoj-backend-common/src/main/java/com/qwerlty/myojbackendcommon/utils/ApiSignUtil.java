package com.qwerlty.myojbackendcommon.utils;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;

import java.nio.charset.StandardCharsets;

/**
 * 后端与沙箱通信的 API 签名工具
 * 使用 HMAC-SHA256 对 timestamp + body 签名，防止篡改与重放
 */
public final class ApiSignUtil {

    private static final String SEP = "\n";

    /**
     * 生成签名：HMAC-SHA256(secretKey, timestamp + "\n" + body)，输出十六进制字符串
     *
     * @param secretKey 与沙箱共享的密钥
     * @param timestamp 时间戳（毫秒）
     * @param body      请求体原文
     * @return 签名字符串（小写十六进制）
     */
    public static String sign(String secretKey, long timestamp, String body) {
        String payload = timestamp + SEP + (body != null ? body : "");
        HMac hmac = new HMac(HmacAlgorithm.HmacSHA256, secretKey.getBytes(StandardCharsets.UTF_8));
        byte[] digest = hmac.digest(payload.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(digest);
    }

    /**
     * 验证签名
     *
     * @param secretKey 与后端共享的密钥
     * @param timestamp 请求头中的时间戳
     * @param body      请求体原文
     * @param signature 请求头中的签名
     * @return 是否通过
     */
    public static boolean verify(String secretKey, long timestamp, String body, String signature) {
        if (secretKey == null || signature == null || signature.isEmpty()) {
            return false;
        }
        String expected = sign(secretKey, timestamp, body);
        return expected.equalsIgnoreCase(signature.trim());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private ApiSignUtil() {
    }
}
