package br.com.plataforma.comum.web;

import org.springframework.http.HttpStatus;

/** 400: dado inválido ({@code DADOS_INVALIDOS} se não houver código mais específico). */
public class BadRequestException extends ApiException {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message, "DADOS_INVALIDOS");
    }

    public BadRequestException(String message, String codigo) {
        super(HttpStatus.BAD_REQUEST, message, codigo);
    }
}
