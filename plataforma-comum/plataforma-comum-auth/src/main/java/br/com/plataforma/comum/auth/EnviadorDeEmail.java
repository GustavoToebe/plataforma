package br.com.plataforma.comum.auth;

/**
 * Envio de e-mail do módulo. As implementações são {@code EnviadorResend} (HTTP) e {@code EnviadorRegistro} (só registra que houve
 * envio, para dev e teste). Um bean do sistema do mesmo tipo toma o lugar das duas, o que serve para um teste do sistema capturar
 * o link. Quem monta o texto é o {@link EmailsDeAcesso}: o módulo só envia o link e um texto fixo.
 */
public interface EnviadorDeEmail {

    /** Texto e HTML já prontos; {@code toString} não mostra nada, para a mensagem não chegar a um log. */
    record Mensagem(String para, String assunto, String texto, String html) {
        @Override
        public String toString() {
            return "Mensagem[***]";
        }
    }

    /** @throws EnvioDeEmailException se o provedor não aceitou a mensagem */
    void enviar(Mensagem mensagem);
}
