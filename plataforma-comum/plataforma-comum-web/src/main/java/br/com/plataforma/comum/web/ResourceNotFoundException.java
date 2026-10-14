package br.com.plataforma.comum.web;

import org.springframework.http.HttpStatus;

/** 404: registro inexistente (ou de outro dono, para não revelar que existe). */
public class ResourceNotFoundException extends ApiException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message, null);
    }

    public ResourceNotFoundException(String message, String codigo) {
        super(HttpStatus.NOT_FOUND, message, codigo);
    }
}
