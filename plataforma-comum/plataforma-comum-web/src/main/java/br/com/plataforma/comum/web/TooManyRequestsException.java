package br.com.plataforma.comum.web;

import org.springframework.http.HttpStatus;

/** 429: limite de uso atingido. */
public class TooManyRequestsException extends ApiException {

    private static final long serialVersionUID = 1L;

    public TooManyRequestsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message, null);
    }

    public TooManyRequestsException(String message, String codigo) {
        super(HttpStatus.TOO_MANY_REQUESTS, message, codigo);
    }
}
