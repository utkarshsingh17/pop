package ai.utkarsh.pop.infrastructure.investigator.logs;

import ai.utkarsh.pop.domain.model.LogSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fetches the tail of a service's log.
 *
 * <p>Only the tail, and only ever a bounded amount: a log can be gigabytes, and the consumer is a
 * language model with a context window. Both sources support reading from the end — a file by
 * seeking, the actuator endpoint by HTTP Range — so neither has to transfer the whole thing.
 */
@Slf4j
@Component
public class LogReader {

    /** Enough to hold a stack trace and the lines around it, small enough to reason over. */
    static final int TAIL_BYTES = 64 * 1024;

    private final RestClient.Builder builder;

    LogReader(RestClient.Builder builder) {
        this.builder = builder;
    }

    /** @return the last {@link #TAIL_BYTES} of the log, or empty when there is nothing to read */
    public String tail(LogSource source) {
        return switch (source.kind()) {
            case FILE -> tailFile(Path.of(source.location()));
            case ACTUATOR -> tailOverHttp(source.location());
        };
    }

    private String tailFile(Path path) {
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("Log file is not readable: " + path);
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            long from = Math.max(0, length - TAIL_BYTES);
            file.seek(from);
            byte[] buffer = new byte[(int) Math.min(length, TAIL_BYTES)];
            file.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            // Seeking to a byte offset lands mid-line; drop the partial first line.
            int firstBreak = text.indexOf('\n');
            return from > 0 && firstBreak >= 0 ? text.substring(firstBreak + 1) : text;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read log file " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@code /actuator/logfile} honours HTTP Range, so the tail costs one small request.
     *
     * <p>A 416 means the file is smaller than the requested window, which is not an error — ask
     * again for the whole thing.
     */
    private String tailOverHttp(String url) {
        RestClient client = builder.clone().build();
        String ranged = client.get()
                .uri(url)
                .header(HttpHeaders.RANGE, "bytes=-" + TAIL_BYTES)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 416) {
                        return null;
                    }
                    return response.bodyTo(String.class);
                });
        if (ranged != null) {
            return ranged;
        }
        String whole = client.get().uri(url).retrieve().body(String.class);
        return whole == null ? "" : whole;
    }
}
