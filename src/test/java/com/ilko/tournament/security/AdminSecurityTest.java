package com.ilko.tournament.security;

import com.ilko.tournament.controller.AdminController;
import com.ilko.tournament.dto.UserResponse;
import com.ilko.tournament.service.UserAdminServiceApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class)
class AdminSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserAdminServiceApi service;
    @MockitoBean AppUserDetailsService userDetailsService;

    @Test
    void administrationRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "organizer", roles = "ORGANIZER")
    void organizerCannotOpenAdministration() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "georgi", roles = "PARTICIPANT")
    void participantCannotChangeRoles() throws Exception {
        mvc.perform(put("/api/admin/users/2/role").contentType(APPLICATION_JSON).content("{\"role\":\"ADMINISTRATOR\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMINISTRATOR")
    void administratorListsUsersWithoutPasswords() throws Exception {
        when(service.users()).thenReturn(List.of(new UserResponse(1L, "admin", "admin@example.com", "ADMINISTRATOR", true, null)));

        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMINISTRATOR")
    void unknownRoleIsRejectedAsBadRequest() throws Exception {
        mvc.perform(put("/api/admin/users/2/role").contentType(APPLICATION_JSON).content("{\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }
}
