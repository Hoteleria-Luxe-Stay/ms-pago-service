package com.hotel.pago.infrastructure.security;

import com.hotel.pago.internal.dto.AuthTokenValidationResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthContextFilterTest {

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private AuthContextFilter filter;

    @Test
    void setsAuthContextWhenValidBearerTokenWithUserIdAndRoles() throws Exception {
        String token = "valid.jwt.token";
        Jwt jwt = buildJwt("user@test.com", 42L, "ROLE_ADMIN");

        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token);
        when(jwtDecoder.decode(token)).thenReturn(jwt);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<AuthTokenValidationResponse> captor =
                ArgumentCaptor.forClass(AuthTokenValidationResponse.class);
        verify(request).setAttribute(eq(AuthContextFilter.AUTH_CONTEXT_KEY), captor.capture());

        AuthTokenValidationResponse auth = captor.getValue();
        assertThat(auth.getValid()).isTrue();
        assertThat(auth.getEmail()).isEqualTo("user@test.com");
        assertThat(auth.getUserId()).isEqualTo(42L);
        assertThat(auth.getRole()).isEqualTo("ADMIN"); // ROLE_ prefix stripped
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void setsAuthContextWhenTokenHasUserIdButNoRoles() throws Exception {
        String token = "valid.jwt.nrole";
        Jwt jwt = buildJwtWithoutRoles("user@test.com", 5L);

        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + token);
        when(jwtDecoder.decode(token)).thenReturn(jwt);

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<AuthTokenValidationResponse> captor =
                ArgumentCaptor.forClass(AuthTokenValidationResponse.class);
        verify(request).setAttribute(eq(AuthContextFilter.AUTH_CONTEXT_KEY), captor.capture());
        assertThat(captor.getValue().getRole()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotSetAuthContextWhenNoAuthorizationHeader() throws Exception {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(request, never()).setAttribute(any(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotSetAuthContextWhenAuthorizationIsNotBearer() throws Exception {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Basic abc123");

        filter.doFilterInternal(request, response, filterChain);

        verify(request, never()).setAttribute(any(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotSetAuthContextWhenJwtDecoderThrows() throws Exception {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer bad.token");
        when(jwtDecoder.decode("bad.token")).thenThrow(new JwtException("invalid signature"));

        filter.doFilterInternal(request, response, filterChain);

        verify(request, never()).setAttribute(any(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void doesNotSetAuthContextWhenBothUserIdAndRolesAreNull() throws Exception {
        Jwt jwt = buildJwtNoUserIdNoRoles("anon@test.com");

        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer anon.token");
        when(jwtDecoder.decode("anon.token")).thenReturn(jwt);

        filter.doFilterInternal(request, response, filterChain);

        verify(request, never()).setAttribute(any(), any());
        verify(filterChain).doFilter(request, response);
    }

    // ==================== helpers ====================

    private Jwt buildJwt(String subject, Long userId, String roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("userId", userId)
                .claim("roles", roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private Jwt buildJwtWithoutRoles(String subject, Long userId) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("userId", userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private Jwt buildJwtNoUserIdNoRoles(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
