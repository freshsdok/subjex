package com.subjex.platform.app.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * ProductionConfigChecklistTest — purpose/gate WP1-5: lock the production-config checklist
 * claims against profile yaml and the default k8s manifest (no new runtime subsystem).
 * <p>
 * 目的/门禁 WP1-5：用生产 yaml 与默认 k8s 清单锁定发布检查表中的配置断言（不新增运行时子系统）。
 */
class ProductionConfigChecklistTest {

    @Test
    void productionProfilesMatchChecklistFlags() throws IOException {
        assertEquals("true", property("platform.mfa.required", "prod"));
        assertEquals("true", property("platform.mfa.required", "production"));
        assertEquals("false", property("platform.tenant.auto-create-on-submit", "prod"));
        assertEquals("false", property("platform.tenant.auto-create-on-submit", "production"));
        assertEquals("s3", property("platform.storage.type", "prod"));
        assertEquals("s3", property("platform.storage.type", "production"));
        // HTTP Basic stays off: base default, and prod/production do not turn it on.
        // HTTP Basic 保持关闭：基础默认即关，生产 profile 不再打开。
        assertEquals("false", property("platform.auth.http-basic-enabled"));
        assertEquals("false", property("platform.auth.http-basic-enabled", "prod"));
        assertEquals("false", property("platform.auth.http-basic-enabled", "production"));
        // Single-replica prod is not surprised by a silent JDBC-backend flip.
        // 单副本生产不会被静默改成 JDBC 后端。
        assertEquals("process", property("platform.rate-limit.backend", "prod"));
        assertEquals("process", property("platform.delivery.circuit-breaker.backend", "prod"));
        // SMS OTP sender stays fail-closed in production; codes are never revealed.
        // 生产短信验证码发送器保持失败关闭；绝不在响应中出示验证码。
        assertEquals("false", property("platform.end-user.auth.verification-code.reveal-in-response"));
        assertEquals("false", property("platform.end-user.auth.verification-code.reveal-in-response", "prod"));
        assertEquals("false", property("platform.end-user.auth.verification-code.reveal-in-response", "production"));
        assertEquals("", property("platform.end-user.auth.verification-code.sender"));
        assertEquals("", property("platform.end-user.auth.verification-code.sender", "prod"));
        assertEquals("", property("platform.end-user.auth.verification-code.sender", "production"));
        assertEquals("", property("platform.end-user.auth.verification-code.http.api-key", "prod"));
        // Notification adapters stay fail-closed in production (empty selector).
        // 生产通知渠道保持失败关闭（选择键为空）。
        assertEquals("", property("platform.notification.sms.adapter"));
        assertEquals("", property("platform.notification.sms.adapter", "prod"));
        assertEquals("", property("platform.notification.sms.adapter", "production"));
        assertEquals("", property("platform.notification.email.adapter", "prod"));
        assertEquals("", property("platform.notification.wechat-template.adapter", "prod"));
        assertEquals("", property("platform.notification.sms.http.api-key", "prod"));
        // Payment adapters stay fail-closed in production (empty selector).
        // 生产支付适配器保持失败关闭（选择键为空）。
        assertEquals("", property("platform.payment.mock.adapter"));
        assertEquals("", property("platform.payment.mock.adapter", "prod"));
        assertEquals("", property("platform.payment.mock.adapter", "production"));
        assertEquals("", property("platform.payment.wechat-pay.adapter", "prod"));
        assertEquals("", property("platform.payment.alipay.adapter", "prod"));
        assertEquals("", property("platform.payment.wechat-pay.http.api-key", "prod"));
        assertEquals("", property("platform.payment.alipay.http.merchant-id", "prod"));
    }

    @Test
    void localProfileKeepsLaptopEscapesAndBaseStaysFailClosedWhereDocumented() throws IOException {
        assertEquals("false", property("platform.mfa.required"));
        assertEquals("false", property("platform.mfa.required", "local"));
        assertEquals("false", property("platform.tenant.auto-create-on-submit"));
        assertEquals("true", property("platform.tenant.auto-create-on-submit", "local"));
        assertEquals("local", property("platform.storage.type"));
        assertEquals("local", property("platform.storage.type", "local"));
        assertEquals("true", property("platform.auth.http-basic-enabled", "local"));
        assertEquals("true", property("platform.end-user.auth.verification-code.reveal-in-response", "local"));
        assertEquals("dev", property("platform.end-user.auth.verification-code.sender", "local"));
        assertEquals("mock", property("platform.notification.sms.adapter", "local"));
        assertEquals("mock", property("platform.notification.email.adapter", "local"));
        assertEquals("mock", property("platform.notification.wechat-template.adapter", "local"));
        assertEquals("mock", property("platform.payment.mock.adapter", "local"));
        assertEquals("", property("platform.payment.wechat-pay.adapter", "local"));
        assertEquals("", property("platform.payment.alipay.adapter", "local"));
    }

    @Test
    void defaultPlatformAppManifestPinsProdAndDoesNotAdvertiseSharedJdbc() throws IOException {
        Resource resource = new ClassPathResource("k8s/platform-app.yaml");
        assertTrue(resource.exists(), "k8s/platform-app.yaml must be on the test classpath");
        String yaml = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(yaml.contains("replicas: 1"), "default manifest must stay replicas: 1");

        Map<String, String> env = containerEnv(yaml);
        assertEquals("prod", env.get("SPRING_PROFILES_ACTIVE"));
        assertEquals("s3", env.get("PLATFORM_STORAGE_TYPE"));
        assertTrue(
                yaml.contains("name: platform-object-storage"),
                "S3 settings must use Secret platform-object-storage placeholders");
        assertFalse(
                env.containsKey("PLATFORM_RATE_LIMIT_BACKEND"),
                "default manifest must not silently set PLATFORM_RATE_LIMIT_BACKEND=jdbc");
        assertFalse(
                env.containsKey("PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND"),
                "default manifest must not silently set PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND=jdbc");
        assertFalse(yaml.contains("platform-operator"), "non-dev manifest must not seed platform-operator");
        assertFalse(env.containsValue("change-me"), "non-dev manifest must not ship change-me values");
        assertFalse(
                "dev".equals(env.get("PLATFORM_END_USER_VERIFICATION_CODE_SENDER")),
                "default manifest must not enable the dev verification-code sender");
        assertFalse(
                env.containsKey("PLATFORM_END_USER_VERIFICATION_CODE_HTTP_API_KEY"),
                "default manifest must not ship an SMS OTP API key");
        assertFalse(
                "mock".equals(env.get("PLATFORM_NOTIFICATION_SMS_ADAPTER")),
                "default manifest must not enable the mock notification SMS adapter");
        assertFalse(
                env.containsKey("PLATFORM_NOTIFICATION_SMS_HTTP_API_KEY"),
                "default manifest must not ship a notification SMS API key");
        assertFalse(
                env.containsKey("PLATFORM_NOTIFICATION_EMAIL_HTTP_API_KEY"),
                "default manifest must not ship a notification email API key");
        assertFalse(
                env.containsKey("PLATFORM_NOTIFICATION_WECHAT_TEMPLATE_HTTP_API_KEY"),
                "default manifest must not ship a notification WeChat API key");
        assertFalse(
                "mock".equals(env.get("PLATFORM_PAYMENT_MOCK_ADAPTER")),
                "default manifest must not enable the mock payment adapter");
        assertFalse(
                env.containsKey("PLATFORM_PAYMENT_WECHAT_PAY_HTTP_API_KEY"),
                "default manifest must not ship a WeChat Pay API key");
        assertFalse(
                env.containsKey("PLATFORM_PAYMENT_ALIPAY_HTTP_API_KEY"),
                "default manifest must not ship an Alipay API key");
        assertFalse(
                env.containsKey("PLATFORM_PAYMENT_WECHAT_PAY_HTTP_MERCHANT_ID"),
                "default manifest must not ship a WeChat Pay merchant id");
        assertFalse(
                env.containsKey("PLATFORM_PAYMENT_ALIPAY_HTTP_MERCHANT_ID"),
                "default manifest must not ship an Alipay merchant id");
    }

    private static String property(String key, String... profiles) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        addYaml(environment, loader, "application.yml", false);
        for (String profile : profiles) {
            addYaml(environment, loader, "application-" + profile + ".yml", true);
        }
        String value = environment.getProperty(key);
        assertNotNull(value, () -> key + " must be set in loaded yaml");
        return value;
    }

    private static void addYaml(
            StandardEnvironment environment, YamlPropertySourceLoader loader, String name, boolean override)
            throws IOException {
        Resource resource = new ClassPathResource(name);
        assertTrue(resource.exists(), () -> "missing classpath resource " + name);
        List<PropertySource<?>> sources = loader.load(name, resource);
        for (int i = sources.size() - 1; i >= 0; i--) {
            PropertySource<?> source = sources.get(i);
            if (override) {
                environment.getPropertySources().addFirst(source);
            } else {
                environment.getPropertySources().addLast(source);
            }
        }
    }

    /**
     * Collect container env name/value pairs from the first Deployment in the manifest.
     * 从清单第一个 Deployment 收集容器环境变量名与值（不含 Secret 引用的具体值）。
     */
    private static Map<String, String> containerEnv(String yaml) {
        Map<String, String> env = new LinkedHashMap<>();
        boolean inEnv = false;
        String currentName = null;
        for (String raw : yaml.split("\n")) {
            String line = raw.stripTrailing();
            if (!inEnv) {
                if (line.matches("\\s+env:\\s*")) {
                    inEnv = true;
                }
                continue;
            }
            if (line.matches("\\s+(livenessProbe|readinessProbe|volumeMounts|resources|ports):\\s*")) {
                break;
            }
            String namePrefix = "            - name: ";
            String valuePrefix = "              value: ";
            if (line.startsWith(namePrefix)) {
                currentName = line.substring(namePrefix.length()).trim();
            } else if (currentName != null && line.startsWith(valuePrefix)) {
                String value = line.substring(valuePrefix.length()).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                env.put(currentName, value);
                currentName = null;
            } else if (currentName != null && line.contains("valueFrom:")) {
                // Secret/config refs have no literal value — record presence with a marker.
                // Secret/Config 引用没有字面值，仅记录存在。
                env.putIfAbsent(currentName, "(valueFrom)");
                currentName = null;
            }
        }
        assertFalse(env.isEmpty(), "expected to parse container env from platform-app.yaml");
        return env;
    }
}
