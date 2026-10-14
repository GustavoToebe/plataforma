package br.com.plataforma.comum.web;

import org.springframework.http.HttpStatus;

/** 413 {@code CORPO_EXCEDIDO}: o corpo (ou o upload) passou do limite permitido. */
public class CorpoExcedidoException extends ApiException {

    private static final long serialVersionUID = 1L;

    public CorpoExcedidoException() {
        super(HttpStatus.CONTENT_TOO_LARGE, "O conteúdo enviado é maior que o permitido.", "CORPO_EXCEDIDO");
    }
}
