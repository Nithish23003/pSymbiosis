package com.psiog.perfinsight.dashboard;

import com.psiog.perfinsight.dashboard.DashboardDtos.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "Email + password login")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    @Operation(summary = "Log in with email + password; returns a Bearer token and the user")
    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest r) {
        return auth.login(r.email(), r.password());
    }

    @Operation(summary = "Who is logged in (validates the token)")
    @GetMapping("/me")
    public UserDto me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return UserDto.of(auth.requireUser(authorization));
    }
}