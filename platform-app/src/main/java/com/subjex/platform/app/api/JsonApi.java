package com.subjex.platform.app.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JsonApi — JSON 接口：给即将到来的 {@code web/} Next.js 应用读写的 {@code /api/v1}，以及它的 OpenAPI 说明。
 * <p>
 * Every operator page has a JSON twin under {@link #BASE}. Each request carries HTTP Basic; there is no session.
 * The description is served at {@code /api/v1/openapi.json} and itself needs a signed-in operator.
 * 每个操作页在 {@link #BASE} 下都有一个 JSON 孪生接口。每次请求都带 HTTP Basic，没有会话。
 * 说明文档在 {@code /api/v1/openapi.json}，取它本身也需要已登录的操作员。
 */
@Configuration
public class JsonApi {

    /** Path prefix of every JSON API endpoint — 所有 JSON 接口的路径前缀。 */
    public static final String BASE = "/api/v1";

    /** Name of the security scheme in the description — 说明文档里安全方案的名字。 */
    static final String BASIC_SCHEME = "operatorBasic";

    /**
     * Title, license, and one global HTTP Basic requirement.
     * 标题、许可证，以及一条全局 HTTP Basic 要求。
     */
    @Bean
    OpenAPI subjexOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("subjex")
                        .description("JSON API behind the operator pages — 操作页背后的 JSON 接口")
                        .version("v1")
                        .license(new License()
                                .name("Apache-2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components().addSecuritySchemes(BASIC_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("basic")
                        .description("Operator login and password on every request — 每次请求都带操作员登录名和口令")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_SCHEME));
    }

    /**
     * Every operation can answer 401 (not signed in) and 403 (missing the permission).
     * 每个操作都可能回 401（没登录）和 403（缺这项权限）。
     */
    @Bean
    OpenApiCustomizer refusalResponses() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses();
                if (responses == null) {
                    responses = new ApiResponses();
                    operation.setResponses(responses);
                }
                responses.putIfAbsent("401", new ApiResponse()
                        .description("No operator signed in — 没有已登录的操作员"));
                responses.putIfAbsent("403", new ApiResponse()
                        .description("Operator lacks the permission — 操作员缺少这项权限"));
            }));
        };
    }
}
