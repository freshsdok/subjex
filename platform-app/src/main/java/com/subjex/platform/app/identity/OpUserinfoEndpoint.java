package com.subjex.platform.app.identity;

import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * OpUserinfoEndpoint — OIDC UserInfo (ID-6). Bearer access (JWT or opaque).
 * OIDC UserInfo：Bearer 访问令牌（JWT 或不透明）。
 */
@RestController
public class OpUserinfoEndpoint {

    public static final String PATH = "/oauth2/userinfo";

    @GetMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> userinfo(@AuthenticationPrincipal OperatorPrincipal operator) {
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub", operator.subjectId());
        body.put("preferred_username", operator.getUsername());
        if (operator.serviceAccount()) {
            body.put("client_id", operator.getUsername());
            if (operator.boundTenantId() != null) {
                body.put("tenant_id", operator.boundTenantId());
            }
        }
        return body;
    }
}
