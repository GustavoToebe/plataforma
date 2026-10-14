package br.com.plataforma.comum.web;

import org.springframework.http.HttpStatus;

/** 409: o pedido conflita com o estado atual. Sem código padrão: o chamador informa o código estável. */
public class ConflictException extends ApiException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message, null);
    }

    public ConflictException(String message, String codigo) {
        super(HttpStatus.CONFLICT, message, codigo);
    }
}
