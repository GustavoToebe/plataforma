package br.com.plataforma.comum.web;

import org.springframework.http.HttpStatus;

/** Erro de negócio com status HTTP e código estável; o {@link GlobalExceptionHandler} o devolve como {@link ApiError}. */
public abstract class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String codigo;

    protected ApiException(HttpStatus status, String message) {
        this(status, message, null, null);
    }

    protected ApiException(HttpStatus status, String message, String codigo) {
        this(status, message, codigo, null);
    }

    protected ApiException(HttpStatus status, String message, String codigo, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.codigo = codigo;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCodigo() {
        return codigo;
    }
}
