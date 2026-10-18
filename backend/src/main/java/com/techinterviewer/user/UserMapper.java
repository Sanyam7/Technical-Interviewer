package com.techinterviewer.user;

import com.techinterviewer.user.dto.UserResponse;

/** Maps accounts to their public view. Outside the dto package so that DTOs never depend on entities. */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }
}
