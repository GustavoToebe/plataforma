package br.com.plataforma.comum.web;

import org.springframework.http.HttpStatus;

/** 403: sem permissão. */
public class ForbiddenException extends ApiException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message, null);
    }

    public ForbiddenException(String message, String codigo) {
        super(HttpStatus.FORBIDDEN, message, codigo);
    }
}
