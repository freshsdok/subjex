package com.subjex.platform.app.admin;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.OutboxEvent;
import com.subjex.platform.contract.task.TaskMessagePort;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * DeadLetterApiEndpoint — list / replay / discard outbox (and task) dead letters (DLQ polish).
 * JSON twin for console; admin.read. Replay only OUTBOX_EVENT origins.
 * 死信列表/重放/丢弃；仅出箱来源可重放。
 */
@RestController
public class DeadLetterApiEndpoint {

    public static final String PATH = JsonApi.BASE + "/dead-letters";

    private final JdbcAdminReader adminReader;
    private final TaskMessagePort taskMessagePort;
    private final OperatorActionAudit audit;

    public DeadLetterApiEndpoint(
            JdbcAdminReader adminReader, TaskMessagePort taskMessagePort, OperatorActionAudit audit) {
        this.adminReader = Objects.requireNonNull(adminReader, "adminReader");
        this.taskMessagePort = Objects.requireNonNull(taskMessagePort, "taskMessagePort");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @GetMapping(PATH)
    public DeadLettersDocument list() {
        List<DeadLetterDocument> letters = adminReader.deadLetters().stream().map(DeadLetterApiEndpoint::toDoc).toList();
        return new DeadLettersDocument(letters);
    }

    @PostMapping(PATH + "/{deadLetterId}/replay")
    public ReplayDocument replay(
            @AuthenticationPrincipal OperatorPrincipal operator, @PathVariable String deadLetterId) {
        try {
            OutboxEvent event = taskMessagePort.replayDeadLetter(deadLetterId);
            audit.record(operator, "outbox.dead_letter.replay", deadLetterId, AuditOutcome.ALLOWED);
            return new ReplayDocument(
                    deadLetterId,
                    event.eventId(),
                    event.eventState().name(),
                    event.attemptCount(),
                    event.failureReason());
        } catch (IllegalArgumentException ex) {
            throw mapError(ex);
        }
    }

    @PostMapping(PATH + "/{deadLetterId}/discard")
    public DeadLetterDocument discard(
            @AuthenticationPrincipal OperatorPrincipal operator, @PathVariable String deadLetterId) {
        try {
            DeadLetter letter = taskMessagePort.discardDeadLetter(deadLetterId);
            audit.record(operator, "outbox.dead_letter.discard", deadLetterId, AuditOutcome.ALLOWED);
            return toDoc(letter);
        } catch (IllegalArgumentException ex) {
            throw mapError(ex);
        }
    }

    private static ResponseStatusException mapError(IllegalArgumentException ex) {
        String msg = ex.getMessage() == null ? "bad-request" : ex.getMessage();
        HttpStatus status =
                switch (msg) {
                    case "dead-letter-not-found" -> HttpStatus.NOT_FOUND;
                    case "dead-letter-discarded", "dead-letter-not-outbox", "outbox-event-not-dead" ->
                            HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return new ResponseStatusException(status, msg);
    }

    private static DeadLetterDocument toDoc(DeadLetter letter) {
        return new DeadLetterDocument(
                letter.deadLetterId(),
                letter.tenantId(),
                letter.originKind().name(),
                letter.originId(),
                letter.failureReason(),
                letter.recordedAt(),
                letter.discardedAt());
    }

    public record DeadLettersDocument(List<DeadLetterDocument> deadLetters) {}

    public record DeadLetterDocument(
            String deadLetterId,
            String tenantId,
            String originKind,
            String originId,
            String failureReason,
            Instant recordedAt,
            Instant discardedAt) {}

    public record ReplayDocument(
            String deadLetterId, String eventId, String eventState, int attemptCount, String failureReason) {}
}
