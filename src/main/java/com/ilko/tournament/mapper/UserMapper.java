package com.ilko.tournament.mapper;

import com.ilko.tournament.dto.UserResponse;
import com.ilko.tournament.entity.AppUser;

public final class UserMapper {
    private UserMapper() { }

    /** The password hash is deliberately never mapped. */
    public static UserResponse toResponse(AppUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole().name(),
                user.isEnabled(), user.getCreatedAt());
    }
}
