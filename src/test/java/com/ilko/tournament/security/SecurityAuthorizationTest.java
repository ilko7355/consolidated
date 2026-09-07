package com.ilko.tournament.security;

import com.ilko.tournament.controller.TournamentController;
import com.ilko.tournament.service.TournamentServiceApi;
import com.ilko.tournament.exception.ConflictException;
import com.ilko.tournament.exception.ResourceNotFoundException;
import com.ilko.tournament.dto.TournamentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.mockito.Mockito.when;
import static java.util.List.of;
import java.time.LocalDate;

@WebMvcTest(TournamentController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
class SecurityAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean TournamentServiceApi service;
    @MockitoBean AppUserDetailsService userDetailsService;

    @Test
    void protectedEndpointRejectsMissingAuthentication() throws Exception {
        mvc.perform(get("/api/tournaments"))
                .andExpect(status().isUnauthorized());
    }

            @Test
            @WithMockUser(username = "participant", roles = "PARTICIPANT")
            void successfulReadReturnsOk() throws Exception {
            when(service.list()).thenReturn(of());

            mvc.perform(get("/api/tournaments"))
                .andExpect(status().isOk());
            }

            @Test
            @WithMockUser(username = "organizer", roles = "ORGANIZER")
            void createReturnsCreatedAndResponseBody() throws Exception {
            when(service.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TournamentResponse(1L, "Cup", "Description", "ELIMINATION", "REGISTRATION",
                    LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 2), "organizer", 0));

            mvc.perform(post("/api/tournaments")
                    .contentType(APPLICATION_JSON)
                    .content("{\"name\":\"Cup\",\"format\":\"ELIMINATION\",\"startDate\":\"2099-01-01\",\"endDate\":\"2099-01-02\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Cup"));
            }

            @Test
            @WithMockUser(username = "organizer", roles = "ORGANIZER")
            void updateReturnsOk() throws Exception {
            when(service.update(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TournamentResponse(1L, "Updated", null, "ELIMINATION", "REGISTRATION",
                    LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 2), "organizer", 0));

            mvc.perform(put("/api/tournaments/1")
                    .contentType(APPLICATION_JSON)
                    .content("{\"name\":\"Updated\",\"startDate\":\"2099-01-01\",\"endDate\":\"2099-01-02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"));
            }

            @Test
            @WithMockUser(username = "organizer", roles = "ORGANIZER")
            void deleteReturnsNoContent() throws Exception {
            mvc.perform(delete("/api/tournaments/1"))
                .andExpect(status().isNoContent());
            }

            @Test
            @WithMockUser(username = "organizer", roles = "ORGANIZER")
            void invalidRequestReturnsBadRequest() throws Exception {
            mvc.perform(post("/api/tournaments")
                    .contentType(APPLICATION_JSON)
                    .content("{\"format\":\"ELIMINATION\",\"startDate\":\"2099-01-01\",\"endDate\":\"2099-01-02\"}"))
                .andExpect(status().isBadRequest());
            }

            @Test
            @WithMockUser(username = "participant", roles = "PARTICIPANT")
            void missingResourceReturnsNotFound() throws Exception {
            when(service.getResponse(404L)).thenThrow(new ResourceNotFoundException("Tournament not found: 404"));

            mvc.perform(get("/api/tournaments/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/tournaments/404"));
            }

            @Test
            @WithMockUser(username = "organizer", roles = "ORGANIZER")
            void conflictingRequestReturnsConflict() throws Exception {
            when(service.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ConflictException("Tournament already exists"));

            mvc.perform(post("/api/tournaments")
                    .contentType(APPLICATION_JSON)
                    .content("{\"name\":\"Test\",\"format\":\"ELIMINATION\",\"startDate\":\"2099-01-01\",\"endDate\":\"2099-01-02\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
            }

    @Test
    @WithMockUser(username = "participant", roles = "PARTICIPANT")
    void regularUserCannotCreateTournament() throws Exception {
        mvc.perform(post("/api/tournaments")
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Test\",\"format\":\"ELIMINATION\",\"startDate\":\"2099-01-01\",\"endDate\":\"2099-01-02\"}"))
                .andExpect(status().isForbidden());
    }
}