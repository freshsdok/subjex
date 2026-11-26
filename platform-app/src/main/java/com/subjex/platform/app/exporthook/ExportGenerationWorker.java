package com.subjex.platform.app.exporthook;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ExportGenerationWorker — thin sweep that generates approved personal-data export artifacts in the background.
 * 薄清扫：在后台生成已批准的个人资料导出产物。复用已有定时调度钩子，不是一套新的任务平台。
 */
@Component
public final class ExportGenerationWorker {

    private final ExportGenerationService generation;
    private final int batchSize;

    public ExportGenerationWorker(
            ExportGenerationService generation,
            @Value("${platform.export.generate-batch-size:10}") int batchSize) {
        this.generation = Objects.requireNonNull(generation, "generation");
        if (batchSize < 1) {
            throw new IllegalArgumentException("export generate batch size must be at least 1");
        }
        this.batchSize = batchSize;
    }

    /**
     * Drain queued GENERATING (and leftover APPROVED) rows — 领取已入队的 GENERATING（以及遗留的 APPROVED）.
     */
    @Scheduled(fixedDelayString = "${platform.export.generate-interval-ms:2000}")
    public void processQueued() {
        generation.processQueued(batchSize);
    }
}
