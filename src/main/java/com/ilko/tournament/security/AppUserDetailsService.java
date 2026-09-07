package com.ilko.tournament.security;

import com.ilko.tournament.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {
    private final AppUserRepository users;
    @Override public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return users.findByUsername(username).map(user -> User.withUsername(user.getUsername()).password(user.getPassword()).authorities(new SimpleGrantedAuthority("ROLE_" + user.getRole())).disabled(!user.isEnabled()).build()).orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
