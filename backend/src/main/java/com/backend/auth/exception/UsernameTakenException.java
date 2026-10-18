package com.backend.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class UsernameTakenException extends ResponseStatusException {
    public UsernameTakenException() {
        super(HttpStatus.CONFLICT, "This username is already taken. Please choose another.");
    }
}
