package com.subjex.platform.app.inbox;

import com.subjex.platform.app.api.JsonApi;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserInboxEndpoint — retired leftover {@code /api/v1/me/inbox}. Always 410 Gone.
 * The working end-user mailbox is {@link EndUserShellInboxEndpoint} under {@code /api/v1/end-user/inbox}.
 * 已退役的残留路径 {@code /api/v1/me/inbox}，一律返回 410。
 * 终端收件箱产品路径是 {@code /api/v1/end-user/inbox}。不再提供第二套可用的收件接口。
 */
@RestController
public class EndUserInboxEndpoint {

    /** Retired JSON base; kept so tests and docs can name the old path — 已退役的 JSON 根路径，供测试与文档引用. */
    public static final String BASE = JsonApi.BASE + "/me/inbox";

    @RequestMapping(
            path = {BASE, BASE + "/**"},
            method = {
                RequestMethod.GET,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.PATCH,
                RequestMethod.DELETE
            })
    @ResponseStatus(HttpStatus.GONE)
    public void retired() {
        throw new InboxPathRetiredException();
    }
}
