package br.com.plataforma.comum.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provedor {@code log}: não envia nada e registra só que houve envio (nunca destinatário, assunto, link nem token). */
final class EnviadorRegistro implements EnviadorDeEmail {

    private static final Logger LOG = LoggerFactory.getLogger(EnviadorRegistro.class);

    @Override
    public void enviar(Mensagem mensagem) {
        LOG.info("E-mail de acesso registrado, não enviado (plataforma.auth.email.provedor=log).");
    }
}
