package com.subjex.platform.app.idp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import com.subjex.platform.app.security.InvalidOperatorTokenException;

/**
 * HttpWeChatApiClient — live WeChat HTTP (ID-4 sketch).
 * 真实微信 HTTP 调用。
 */
public final class HttpWeChatApiClient implements WeChatApiClient {

    private final WeChatProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public HttpWeChatApiClient(WeChatProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public WeChatSession redeemOpenCode(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidOperatorTokenException("wechat-code-missing");
        }
        String url = "https://api.weixin.qq.com/sns/oauth2/access_token"
                + "?appid=" + enc(properties.appId())
                + "&secret=" + enc(properties.appSecret())
                + "&code=" + enc(code.trim())
                + "&grant_type=authorization_code";
        return getSession(url);
    }

    @Override
    public WeChatSession code2Session(String jsCode) {
        if (jsCode == null || jsCode.isBlank()) {
            throw new InvalidOperatorTokenException("wechat-jscode-missing");
        }
        String url = "https://api.weixin.qq.com/sns/jscode2session"
                + "?appid=" + enc(properties.appId())
                + "&secret=" + enc(properties.appSecret())
                + "&js_code=" + enc(jsCode.trim())
                + "&grant_type=authorization_code";
        return getSession(url);
    }

    private WeChatSession getSession(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("wechat-http-" + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (body.has("errcode") && body.path("errcode").asInt(0) != 0) {
                throw new InvalidOperatorTokenException("wechat-api-" + body.path("errcode").asInt());
            }
            String openId = text(body, "openid");
            if (openId == null || openId.isBlank()) {
                throw new InvalidOperatorTokenException("wechat-openid-missing");
            }
            return new WeChatSession(openId, text(body, "unionid"));
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("wechat-unreachable", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
