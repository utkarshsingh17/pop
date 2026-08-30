package ai.utkarsh.pop.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root: one operator question ("why is the order service slow?") and everything
 * the agent learned while answering it.
 *
 * <p>Pure Java by design — no Spring, no JPA, no Jackson. Persistence lives in
 * {@code infrastructure.persistence} and maps to and from this type, so the rules below stay
 * testable without a container and survive a change of database or framework.
 *
 * <p>The status transitions are enforced here rather than in a service, because they are the
 * one thing that must hold no matter which adapter or use case is driving.
 */
public final class Investigation {

    private static final int MAX_FINDINGS = 200;

    private final InvestigationId id;
    private final String question;
    private final ServiceName service;
    private final TimeRange timeRange;
    private final Instant createdAt;
    private final List<Finding> findings = new ArrayList<>();

    private InvestigationStatus status;
    private Instant startedAt;
    private Instant completedAt;
    private Diagnosis diagnosis;
    private String failureReason;

    private Investigation(InvestigationId id, String question, ServiceName service,
                          TimeRange timeRange, Instant createdAt) {
        this.id = id;
        this.question = question;
        this.service = service;
        this.timeRange = timeRange;
        this.createdAt = createdAt;
        this.status = InvestigationStatus.PENDING;
    }

    /** Opens a new investigation in {@link InvestigationStatus#PENDING}. */
    public static Investigation open(String question, ServiceName service, TimeRange timeRange, Instant now) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        Objects.requireNonNull(service, "service must not be null");
        Objects.requireNonNull(timeRange, "timeRange must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new Investigation(InvestigationId.generate(), question.trim(), service, timeRange, now);
    }

    /**
     * Rehydrates an investigation from storage. Only persistence adapters should call this —
     * it bypasses the lifecycle rules on purpose, because the stored state already satisfied
     * them when it was written.
     */
    public static Investigation rehydrate(InvestigationId id, String question, ServiceName service,
                                          TimeRange timeRange, InvestigationStatus status, Instant createdAt,
                                          Instant startedAt, Instant completedAt, Diagnosis diagnosis,
                                          String failureReason, List<Finding> findings) {
        Investigation investigation = new Investigation(id, question, service, timeRange, createdAt);
        investigation.status = Objects.requireNonNull(status, "status must not be null");
        investigation.startedAt = startedAt;
        investigation.completedAt = completedAt;
        investigation.diagnosis = diagnosis;
        investigation.failureReason = failureReason;
        if (findings != null) {
            investigation.findings.addAll(findings);
        }
        return investigation;
    }

    /** PENDING -> INVESTIGATING. The agent may now gather evidence. */
    public void begin(Instant now) {
        requireStatus(InvestigationStatus.PENDING, "begin");
        this.status = InvestigationStatus.INVESTIGATING;
        this.startedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /**
     * Attaches evidence. Only legal while INVESTIGATING — a finding arriving after the
     * diagnosis would mean the conclusion was drawn from an incomplete picture.
     */
    public void recordFinding(Finding finding) {
        Objects.requireNonNull(finding, "finding must not be null");
        requireStatus(InvestigationStatus.INVESTIGATING, "record a finding");
        if (findings.size() >= MAX_FINDINGS) {
            throw new IllegalStateException(
                    "investigation " + id + " already holds the maximum of " + MAX_FINDINGS + " findings");
        }
        findings.add(finding);
    }

    /** INVESTIGATING -> COMPLETED. */
    public void concludeWith(Diagnosis diagnosis, Instant now) {
        Objects.requireNonNull(diagnosis, "diagnosis must not be null");
        requireStatus(InvestigationStatus.INVESTIGATING, "conclude");
        this.diagnosis = diagnosis;
        this.status = InvestigationStatus.COMPLETED;
        this.completedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Marks the investigation failed. Legal from any non-terminal state. */
    public void fail(String reason, Instant now) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "cannot fail investigation " + id + ": already " + status);
        }
        this.failureReason = (reason == null || reason.isBlank()) ? "unknown failure" : reason;
        this.status = InvestigationStatus.FAILED;
        this.completedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Highest severity observed so far, or {@link Severity#INFO} when nothing was found. */
    public Severity highestSeverity() {
        return findings.stream()
                .map(Finding::severity)
                .max(Severity::compareTo)
                .orElse(Severity.INFO);
    }

    public List<Finding> findingsFrom(FindingSource source) {
        Objects.requireNonNull(source, "source must not be null");
        return findings.stream().filter(f -> f.source() == source).toList();
    }

    private void requireStatus(InvestigationStatus required, String action) {
        if (status != required) {
            throw new IllegalStateException(
                    "cannot " + action + " on investigation " + id + ": expected " + required + " but was " + status);
        }
    }

    public InvestigationId id() {
        return id;
    }

    public String question() {
        return question;
    }

    public ServiceName service() {
        return service;
    }

    public TimeRange timeRange() {
        return timeRange;
    }

    public InvestigationStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<Diagnosis> diagnosis() {
        return Optional.ofNullable(diagnosis);
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    public List<Finding> findings() {
        return Collections.unmodifiableList(findings);
    }
}
