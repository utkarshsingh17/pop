package ai.utkarsh.pop.infrastructure.web;

import ai.utkarsh.pop.domain.model.DatabaseTarget;
import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.port.in.ManageServicesUseCase;
import ai.utkarsh.pop.domain.port.in.ManageServicesUseCase.ProbeResult;
import ai.utkarsh.pop.domain.port.in.ManageServicesUseCase.ServiceAlreadyRegisteredException;
import ai.utkarsh.pop.domain.port.in.ManageServicesUseCase.ServiceNotFoundException;
import ai.utkarsh.pop.infrastructure.security.SecretKeyNotConfiguredException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MonitoredServiceController.class)
class MonitoredServiceControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManageServicesUseCase manageServices;

    private static MonitoredService registered() {
        return MonitoredService.register(ServiceName.of("order-service"), null,
                new DatabaseTarget("jdbc:postgresql://db:5432/shop", "pop_readonly", ""), NOW);
    }

    @Test
    void register_shouldReturn201WithALocationHeader() throws Exception {
        when(manageServices.register(any())).thenReturn(registered());

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "order-service",
                                  "jdbcUrl": "jdbc:postgresql://db:5432/shop",
                                  "username": "pop_readonly",
                                  "password": "hunter2"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/services/order-service"))
                .andExpect(jsonPath("$.name").value("order-service"))
                .andExpect(jsonPath("$.database.jdbcUrl").value("jdbc:postgresql://db:5432/shop"));
    }

    @Test
    void register_shouldNeverEchoThePasswordBack() throws Exception {
        when(manageServices.register(any())).thenReturn(registered());

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"order-service","jdbcUrl":"jdbc:postgresql://db:5432/shop",
                                 "username":"pop_readonly","password":"hunter2"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.database.password").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void register_shouldRejectABlankName() throws Exception {
        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","jdbcUrl":"jdbc:postgresql://db:5432/shop"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_shouldReturn409WhenAlreadyRegistered() throws Exception {
        when(manageServices.register(any()))
                .thenThrow(new ServiceAlreadyRegisteredException(ServiceName.of("order-service")));

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"order-service"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Service Already Registered"));
    }

    @Test
    void register_shouldReturn503WhenNoSecretKeyIsConfigured() throws Exception {
        when(manageServices.register(any())).thenThrow(new SecretKeyNotConfiguredException());

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"order-service","jdbcUrl":"jdbc:postgresql://db:5432/shop",
                                 "username":"u","password":"p"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("pop.security.secret-key")));
    }

    @Test
    void register_shouldReturn400WhenTheHostIsRejected() throws Exception {
        when(manageServices.register(any()))
                .thenThrow(new IllegalArgumentException("Host '169.254.169.254' resolves to a link-local address"));

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"evil","jdbcUrl":"jdbc:postgresql://169.254.169.254:5432/x",
                                 "username":"u","password":"p"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void byName_shouldReturn404WhenNotRegistered() throws Exception {
        when(manageServices.byName(any()))
                .thenThrow(new ServiceNotFoundException(ServiceName.of("ghost")));

        mockMvc.perform(get("/api/v1/services/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Service Not Registered"));
    }

    @Test
    void all_shouldListRegistrations() throws Exception {
        when(manageServices.all()).thenReturn(List.of(registered()));

        mockMvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("order-service"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void deregister_shouldReturn204() throws Exception {
        mockMvc.perform(delete("/api/v1/services/order-service"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deregister_shouldReturn404WhenNotRegistered() throws Exception {
        doThrow(new ServiceNotFoundException(ServiceName.of("ghost")))
                .when(manageServices).deregister(any());

        mockMvc.perform(delete("/api/v1/services/ghost"))
                .andExpect(status().isNotFound());
    }

    @Test
    void probe_shouldReportFailureAsAResultNotAnError() throws Exception {
        when(manageServices.probe(any()))
                .thenReturn(new ProbeResult(false, "password authentication failed"));

        mockMvc.perform(post("/api/v1/services/order-service/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(false))
                .andExpect(jsonPath("$.detail").value("password authentication failed"));
    }
}
