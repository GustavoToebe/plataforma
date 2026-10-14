package br.com.plataforma.comum.web;

import org.springframework.http.HttpStatus;

/** 401: não autenticado. */
public class UnauthorizedException extends ApiException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message, null);
    }

    public UnauthorizedException(String message, String codigo) {
        super(HttpStatus.UNAUTHORIZED, message, codigo);
    }
}
