package com.subjex.platform.app.idp;

/** Browser redirect start — 浏览器跳转启动。 */
public record IdpAuthStart(String authorizationUrl, String state) {}
