package ai.utkarsh.pop.infrastructure.web;

import ai.utkarsh.pop.domain.model.Confidence;
import ai.utkarsh.pop.domain.model.Diagnosis;
import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.InvestigationId;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.in.GetInvestigationUseCase;
import ai.utkarsh.pop.domain.port.in.StartInvestigationUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InvestigationController.class)
class InvestigationControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StartInvestigationUseCase startInvestigation;

    @MockitoBean
    private GetInvestigationUseCase getInvestigation;

    private static Investigation completedInvestigation() {
        Investigation investigation = Investigation.open("why is the order service slow?",
                ServiceName.of("order-service"), TimeRange.lastly(Duration.ofHours(1), NOW), NOW);
        investigation.begin(NOW);
        investigation.recordFinding(Finding.of(FindingSource.POSTGRES, Severity.HIGH,
                "Slow query", "seq scan on orders", NOW, Map.of("calls", "1200")));
        investigation.concludeWith(new Diagnosis("Missing index on orders.customer_id",
                Confidence.HIGH, "Sequential scan dominates latency.",
                List.of("CREATE INDEX CONCURRENTLY ..."), List.of("Slow query")), NOW);
        return investigation;
    }

    @Test
    void start_shouldReturn201WithLocationAndDiagnosis() throws Exception {
        when(startInvestigation.start(any())).thenReturn(completedInvestigation());

        mockMvc.perform(post("/api/v1/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "why is the order service slow?",
                                 "service": "order-service",
                                 "lookback": "PT1H"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.service").value("order-service"))
                .andExpect(jsonPath("$.highestSeverity").value("HIGH"))
                .andExpect(jsonPath("$.diagnosis.probableRootCause")
                        .value("Missing index on orders.customer_id"))
                .andExpect(jsonPath("$.diagnosis.confidence").value("HIGH"))
                .andExpect(jsonPath("$.findings[0].title").value("Slow query"))
                .andExpect(jsonPath("$.findings[0].evidence.calls").value("1200"));
    }

    @Test
    void start_whenQuestionMissing_shouldReturnProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"service": "order-service"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void start_whenServiceNameIsInvalid_shouldReturn400ProblemDetail() throws Exception {
        when(startInvestigation.start(any()))
                .thenThrow(new IllegalArgumentException("service name may only contain letters"));

        mockMvc.perform(post("/api/v1/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "why slow?", "service": "bad name"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.type").value("https://pop.utkarsh.ai/errors/invalid-request"));
    }

    @Test
    void byId_shouldReturnTheInvestigation() throws Exception {
        Investigation investigation = completedInvestigation();
        when(getInvestigation.byId(any())).thenReturn(investigation);

        mockMvc.perform(get("/api/v1/investigations/{id}", investigation.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(investigation.id().value().toString()))
                .andExpect(jsonPath("$.question").value("why is the order service slow?"));
    }

    @Test
    void byId_whenMissing_shouldReturn404ProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(getInvestigation.byId(any()))
                .thenThrow(new GetInvestigationUseCase.InvestigationNotFoundException(new InvestigationId(id)));

        mockMvc.perform(get("/api/v1/investigations/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Investigation Not Found"))
                .andExpect(jsonPath("$.errorCode").value("INVESTIGATION_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void byId_whenIdIsNotAUuid_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/investigations/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recent_shouldReturnSummaries() throws Exception {
        when(getInvestigation.recent(20)).thenReturn(List.of(completedInvestigation()));

        mockMvc.perform(get("/api/v1/investigations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].service").value("order-service"))
                .andExpect(jsonPath("$[0].findingCount").value(1))
                .andExpect(jsonPath("$[0].probableRootCause")
                        .value("Missing index on orders.customer_id"))
                // Summaries must not carry the full finding list.
                .andExpect(jsonPath("$[0].findings").doesNotExist());
    }
}
