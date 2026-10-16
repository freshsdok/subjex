package com.subjex.platform.app.form.action;

import com.subjex.form.render.DomainActionKey;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.form.DomainActionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers capability domainAction handlers (four keys → {@link CapabilityRunner}).
 * 注册四个 capability 领域动作处理器。
 */
@Configuration
public class DomainActionHandlerConfiguration {

    @Bean
    DomainActionHandler capabilityAlgoHashFingerprintHandler(CapabilityRunner capabilities) {
        return new CapabilityInvokeDomainActionHandler(
                DomainActionKey.CAPABILITY_ALGO_HASH_FINGERPRINT, "algo.hashFingerprint", capabilities);
    }

    @Bean
    DomainActionHandler capabilityAlgoNormalizeWhitespaceHandler(CapabilityRunner capabilities) {
        return new CapabilityInvokeDomainActionHandler(
                DomainActionKey.CAPABILITY_ALGO_NORMALIZE_WHITESPACE,
                "algo.normalizeWhitespace",
                capabilities);
    }

    @Bean
    DomainActionHandler capabilityAiSummarizePreviewHandler(CapabilityRunner capabilities) {
        return new CapabilityInvokeDomainActionHandler(
                DomainActionKey.CAPABILITY_AI_SUMMARIZE_PREVIEW, "ai.summarizePreview", capabilities);
    }

    @Bean
    DomainActionHandler capabilityAiSuggestTitlePreviewHandler(CapabilityRunner capabilities) {
        return new CapabilityInvokeDomainActionHandler(
                DomainActionKey.CAPABILITY_AI_SUGGEST_TITLE_PREVIEW, "ai.suggestTitlePreview", capabilities);
    }
}
