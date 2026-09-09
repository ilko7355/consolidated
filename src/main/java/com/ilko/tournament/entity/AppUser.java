package com.ilko.tournament.entity;

import com.ilko.tournament.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "app_users", uniqueConstraints = {@UniqueConstraint(name = "uk_user_username", columnNames = "username"), @UniqueConstraint(name = "uk_user_email", columnNames = "email")})
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 50) private String username;
    @Column(nullable = false, length = 120) private String email;
    @Column(nullable = false) private String password;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Role role = Role.PARTICIPANT;
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
