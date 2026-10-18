package com.techinterviewer.user;

import com.techinterviewer.common.api.ApiPaths;
import com.techinterviewer.security.AuthenticatedUser;
import com.techinterviewer.security.CurrentUser;
import com.techinterviewer.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.BASE + "/users")
@Tag(name = "Users")
class UserController {

    private final UserService users;

    UserController(UserService users) {
        this.users = users;
    }

    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The signed-in account",
            description = "Reads the account from the database, so the role is the current one even if the access token "
                    + "was issued before it changed.")
    @ApiResponse(responseCode = "200", description = "The account of the access token.")
    UserResponse me(@CurrentUser AuthenticatedUser caller) {
        return users.currentUser(caller.id());
    }
}
