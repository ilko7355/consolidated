package com.ilko.tournament.repository;

import com.ilko.tournament.entity.AppUser;
import com.ilko.tournament.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByRole(Role role);
    long countByRole(Role role);
    long countByEnabledFalse();
    List<AppUser> findAllByOrderByCreatedAtAsc();
}
