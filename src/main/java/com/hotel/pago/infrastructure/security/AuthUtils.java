package com.hotel.pago.infrastructure.security;

import com.hotel.pago.internal.dto.AuthTokenValidationResponse;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;

public final class AuthUtils {

    private AuthUtils() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    public static AuthTokenValidationResponse getAuth(NativeWebRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(AuthContextFilter.AUTH_CONTEXT_KEY, RequestAttributes.SCOPE_REQUEST);
        if (value instanceof AuthTokenValidationResponse response) {
            return response;
        }
        return null;
    }

    public static boolean isAdmin(AuthTokenValidationResponse auth) {
        return auth != null && auth.getRole() != null && "ADMIN".equalsIgnoreCase(auth.getRole());
    }
}
