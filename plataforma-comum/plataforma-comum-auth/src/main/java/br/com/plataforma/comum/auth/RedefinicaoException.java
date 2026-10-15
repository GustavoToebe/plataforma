package br.com.plataforma.comum.auth;

import org.springframework.http.HttpStatus;

import br.com.plataforma.comum.web.ApiException;

/**
 * Erro da redefinição de senha, com código estável. Um código só para o link desconhecido, vencido, já usado ou de conta inativa:
 * quem tem o link não aprende nada sobre o motivo.
 */
public class RedefinicaoException extends ApiException {

    private static final long serialVersionUID = 1L;

    public RedefinicaoException(HttpStatus status, String mensagem, String codigo) {
        super(status, mensagem, codigo);
    }

    static RedefinicaoException linkInvalido() {
        return new RedefinicaoException(HttpStatus.BAD_REQUEST, "Link inválido ou expirado. Peça um novo.", "LINK_INVALIDO");
    }

    static RedefinicaoException senhaLonga() {
        return new RedefinicaoException(HttpStatus.BAD_REQUEST, "A senha pode ter no máximo 72 bytes (cerca de 72 letras sem acento).",
                "SENHA_LONGA");
    }

    static RedefinicaoException senhaObrigatoria() {
        return new RedefinicaoException(HttpStatus.BAD_REQUEST, "Informe a nova senha.", "SENHA_OBRIGATORIA");
    }
}
