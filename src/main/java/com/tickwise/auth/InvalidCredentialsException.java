package com.tickwise.auth;

import com.tickwise.shared.error.ApiException;
import com.tickwise.shared.error.ProblemTypes;
import org.springframework.http.HttpStatus;

/** One message for unknown tenant, unknown email, wrong password and disabled account alike. */
class InvalidCredentialsException extends ApiException {

    InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_CREDENTIALS, "Invalid credentials",
                "The workspace, email or password is incorrect");
    }
}
