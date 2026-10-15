package br.com.plataforma.comum.auth;

/** Falha no envio de um e-mail. A mensagem nunca leva destinatário, link, chave nem a resposta do provedor. */
public class EnvioDeEmailException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EnvioDeEmailException(String mensagem) {
        super(mensagem);
    }
}
