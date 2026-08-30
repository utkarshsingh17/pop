package ai.utkarsh.pop.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A half-open lookback window {@code [from, to)} that every investigator scopes its
 * evidence gathering to.
 */
public record TimeRange(Instant from, Instant to) {

    private static final Duration MAX_WINDOW = Duration.ofDays(7);

    public TimeRange {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be strictly before to: " + from + " .. " + to);
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("time range must not exceed " + MAX_WINDOW);
        }
    }

    /** The window ending now and reaching {@code lookback} into the past. */
    public static TimeRange lastly(Duration lookback, Instant now) {
        Objects.requireNonNull(lookback, "lookback must not be null");
        if (lookback.isZero() || lookback.isNegative()) {
            throw new IllegalArgumentException("lookback must be positive: " + lookback);
        }
        return new TimeRange(now.minus(lookback), now);
    }

    public Duration duration() {
        return Duration.between(from, to);
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(from) && instant.isBefore(to);
    }
}
