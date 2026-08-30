package ai.utkarsh.pop.infrastructure.investigator.prometheus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Prometheus HTTP API response envelope.
 *
 * <p>A sample arrives as {@code [ <unix seconds>, "<value as string>" ]} — a heterogeneous
 * JSON array — which is why {@code value} is a list of raw objects with typed access provided
 * by {@link Sample#numericValue()} rather than being mapped directly to a record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrometheusQueryResponse(String status, Data data, String errorType, String error) {

    public boolean isSuccess() {
        return "success".equals(status);
    }

    /** First sample of the first series, when the query returned anything at all. */
    public Optional<Double> firstValue() {
        if (!isSuccess() || data == null || data.result() == null || data.result().isEmpty()) {
            return Optional.empty();
        }
        return data.result().getFirst().numericValue();
    }

    public List<Sample> samples() {
        if (!isSuccess() || data == null || data.result() == null) {
            return List.of();
        }
        return data.result();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String resultType, List<Sample> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sample(Map<String, String> metric, List<Object> value) {

        /**
         * The sample's value. Prometheus encodes it as a string so it can carry {@code NaN}
         * and {@code +Inf}; those are treated as "no reading" rather than propagated as
         * numbers that would silently break threshold comparisons.
         */
        public Optional<Double> numericValue() {
            if (value == null || value.size() < 2 || value.get(1) == null) {
                return Optional.empty();
            }
            try {
                double parsed = Double.parseDouble(String.valueOf(value.get(1)));
                return (Double.isNaN(parsed) || Double.isInfinite(parsed))
                        ? Optional.empty()
                        : Optional.of(parsed);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
    }
}
