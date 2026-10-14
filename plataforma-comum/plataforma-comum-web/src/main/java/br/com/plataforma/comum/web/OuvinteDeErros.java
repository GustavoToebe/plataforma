package br.com.plataforma.comum.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Gancho para o sistema saber dos erros que o {@link GlobalExceptionHandler} devolve (por exemplo, um relatório de
 * erros para o suporte). Declare um bean deste tipo; vários são chamados em ordem. É chamado nos erros de negócio
 * ({@link ApiException}), no limite de login e no 500. Falha do ouvinte nunca muda a resposta.
 */
@FunctionalInterface
public interface OuvinteDeErros {

    /**
     * @param status   status HTTP devolvido
     * @param codigo   código estável do erro; pode ser {@code null}
     * @param mensagem mensagem devolvida ao cliente (nunca a mensagem interna de uma exceção inesperada)
     * @param request  requisição que falhou
     */
    void registrar(int status, String codigo, String mensagem, HttpServletRequest request);
}
