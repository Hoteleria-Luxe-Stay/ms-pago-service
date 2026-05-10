package com.hotel.pago.infrastructure.security;

import com.hotel.pago.internal.dto.AuthTokenValidationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthUtilsTest {

    @Mock
    private NativeWebRequest webRequest;

    @Test
    void getAuthReturnsNullWhenRequestIsNull() {
        AuthTokenValidationResponse result = AuthUtils.getAuth(null);
        assertThat(result).isNull();
    }

    @Test
    void getAuthReturnsAuthResponseWhenAttributeIsPresent() {
        AuthTokenValidationResponse expected = new AuthTokenValidationResponse();
        expected.setValid(true);
        expected.setEmail("user@test.com");

        when(webRequest.getAttribute(AuthContextFilter.AUTH_CONTEXT_KEY, RequestAttributes.SCOPE_REQUEST))
                .thenReturn(expected);

        AuthTokenValidationResponse result = AuthUtils.getAuth(webRequest);
        assertThat(result).isSameAs(expected);
    }

    @Test
    void getAuthReturnsNullWhenAttributeIsNotAuthTokenValidationResponse() {
        when(webRequest.getAttribute(AuthContextFilter.AUTH_CONTEXT_KEY, RequestAttributes.SCOPE_REQUEST))
                .thenReturn("not-an-auth-response");

        AuthTokenValidationResponse result = AuthUtils.getAuth(webRequest);
        assertThat(result).isNull();
    }

    @Test
    void getAuthReturnsNullWhenAttributeIsMissing() {
        when(webRequest.getAttribute(AuthContextFilter.AUTH_CONTEXT_KEY, RequestAttributes.SCOPE_REQUEST))
                .thenReturn(null);

        AuthTokenValidationResponse result = AuthUtils.getAuth(webRequest);
        assertThat(result).isNull();
    }

    @Test
    void isAdminReturnsTrueWhenRoleIsAdmin() {
        AuthTokenValidationResponse auth = new AuthTokenValidationResponse();
        auth.setRole("ADMIN");

        assertThat(AuthUtils.isAdmin(auth)).isTrue();
    }

    @Test
    void isAdminReturnsTrueWhenRoleIsAdminCaseInsensitive() {
        AuthTokenValidationResponse auth = new AuthTokenValidationResponse();
        auth.setRole("admin");

        assertThat(AuthUtils.isAdmin(auth)).isTrue();
    }

    @Test
    void isAdminReturnsFalseWhenRoleIsUser() {
        AuthTokenValidationResponse auth = new AuthTokenValidationResponse();
        auth.setRole("USER");

        assertThat(AuthUtils.isAdmin(auth)).isFalse();
    }

    @Test
    void isAdminReturnsFalseWhenRoleIsNull() {
        AuthTokenValidationResponse auth = new AuthTokenValidationResponse();
        auth.setRole(null);

        assertThat(AuthUtils.isAdmin(auth)).isFalse();
    }

    @Test
    void isAdminReturnsFalseWhenAuthIsNull() {
        assertThat(AuthUtils.isAdmin(null)).isFalse();
    }

    @Test
    void constructorThrowsUnsupportedOperationException() {
        assertThatThrownBy(() -> {
            var constructor = AuthUtils.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance();
        }).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
