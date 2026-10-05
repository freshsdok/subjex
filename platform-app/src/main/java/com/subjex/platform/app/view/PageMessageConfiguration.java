package com.subjex.platform.app.view;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PageMessageConfiguration — 把页面标题目录注册成 Spring 的 messageSource。
 * <p>
 * ApplicationContext looks for this bean name. There is no second catalog.
 * ApplicationContext 找的就是这个 bean 名。没有第二份目录。
 */
@Configuration
public class PageMessageConfiguration {

    @Bean(name = "messageSource")
    MessageSource messageSource() {
        return OperatorPage.messages();
    }
}
