package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.api.JsonApi;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserAuthEndpoint — WP2-2 refresh and logout for end-user tokens (ADR 0014).
 * 终端令牌的刷新与退出。
 * <p>
 * Phone register/login lives on {@link EndUserPhoneAuthEndpoint} under this path prefix (WP2-3).
 * 手机号注册/登录见同前缀下的 {@link EndUserPhoneAuthEndpoint}。微信登录属后续切片。
 */
@RestController
public class EndUserAuthEndpoint {

    public static final String PATH = JsonApi.BASE + "/end-user/auth";

    private final JdbcEndUserTokenStore tokenStore;

    public EndUserAuthEndpoint(JdbcEndUserTokenStore tokenStore) {
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
    }

    @PostMapping(PATH + "/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest body, HttpServletRequest request) {
        String refreshToken = body == null ? null : body.refreshToken();
        try {
            JdbcEndUserTokenStore.IssuedTokens issued =
                    tokenStore.refresh(refreshToken, request.getHeader("User-Agent"), clientIp(request));
            return ResponseEntity.ok(toPublicResponse(issued));
        } catch (InvalidEndUserTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping(PATH + "/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body) {
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            tokenStore.revokeByRefreshToken(body.refreshToken());
        }
        return ResponseEntity.noContent().build();
    }

    static TokenResponse toPublicResponse(JdbcEndUserTokenStore.IssuedTokens issued) {
        return new TokenResponse(
                issued.accessToken(),
                issued.refreshToken(),
                issued.expiresInSeconds(),
                "Bearer");
    }

    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public record RefreshRequest(String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, String tokenType) {}
}
