package com.addzero.miniapp;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class WechatPayClient {
    private static final String API_HOST = "https://api.mch.weixin.qq.com";
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    boolean configured() {
        return notBlank(env("WECHAT_MCH_ID"))
            && notBlank(env("WECHAT_APP_ID"))
            && notBlank(env("WECHAT_API_V3_KEY"))
            && notBlank(env("WECHAT_MCH_SERIAL_NO"))
            && notBlank(env("WECHAT_MCH_PRIVATE_KEY"))
            && notBlank(env("WECHAT_OPENID"));
    }

    Map<String, Object> createJsapiPayment(String orderId, int amountCent, String description) throws Exception {
        String body = MiniJson.toJson(Map.of(
            "appid", env("WECHAT_APP_ID"),
            "mchid", env("WECHAT_MCH_ID"),
            "description", description,
            "out_trade_no", orderId,
            "notify_url", env("WECHAT_NOTIFY_URL"),
            "amount", Map.of("total", amountCent, "currency", "CNY"),
            "payer", Map.of("openid", env("WECHAT_OPENID"))
        ));
        String path = "/v3/pay/transactions/jsapi";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String requestMessage = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = sign(env("WECHAT_MCH_PRIVATE_KEY"), requestMessage);

        HttpRequest request = HttpRequest.newBuilder(URI.create(API_HOST + path))
            .timeout(Duration.ofSeconds(12))
            .header("Authorization", authorization(signature, timestamp, nonce))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Wechat pay create order failed: " + response.statusCode() + " " + response.body());
        }

        String prepayId = extractJsonString(response.body(), "prepay_id");
        String appId = env("WECHAT_APP_ID");
        String packageValue = "prepay_id=" + prepayId;
        String paySign = sign(env("WECHAT_MCH_PRIVATE_KEY"), appId + "\n" + timestamp + "\n" + nonce + "\n" + packageValue + "\n");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mock", false);
        result.put("timeStamp", timestamp);
        result.put("nonceStr", nonce);
        result.put("package", packageValue);
        result.put("signType", "RSA");
        result.put("paySign", paySign);
        return result;
    }

    private String authorization(String signature, String timestamp, String nonce) {
        return "WECHATPAY2-SHA256-RSA2048 "
            + "mchid=\"" + env("WECHAT_MCH_ID") + "\","
            + "nonce_str=\"" + nonce + "\","
            + "signature=\"" + signature + "\","
            + "timestamp=\"" + timestamp + "\","
            + "serial_no=\"" + env("WECHAT_MCH_SERIAL_NO") + "\"";
    }

    private String sign(String privateKey, String message) throws GeneralSecurityException {
        byte[] bytes = Base64.getDecoder().decode(stripPem(privateKey));
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private String stripPem(String value) {
        return value.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");
    }

    private String extractJsonString(String body, String field) {
        String token = "\"" + field + "\"";
        int index = body.indexOf(token);
        if (index < 0) {
            throw new IllegalArgumentException("Missing field " + field + " in response: " + body);
        }
        int colon = body.indexOf(':', index + token.length());
        int firstQuote = body.indexOf('"', colon + 1);
        int secondQuote = body.indexOf('"', firstQuote + 1);
        return body.substring(firstQuote + 1, secondQuote);
    }

    private String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value.trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
