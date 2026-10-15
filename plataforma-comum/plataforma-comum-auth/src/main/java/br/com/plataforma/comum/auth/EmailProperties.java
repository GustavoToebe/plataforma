package br.com.plataforma.comum.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Envio de e-mail do módulo ({@code plataforma.auth.email.*}): só leva o link de redefinição de senha e de convite. Desligado
 * por padrão; ligado, o remetente e o nome do aplicativo são obrigatórios (vêm da configuração de cada app) e, com o provedor
 * {@code resend}, a chave também, sem valor padrão.
 *
 * @param habilitado liga o envio ({@link EnviadorDeEmail} e {@link EmailsDeAcesso})
 * @param provedor   {@code log} (padrão: só registra que houve envio, nunca o destinatário nem o link) ou {@code resend}
 * @param remetente  {@code Nome <endereco@dominio>} ou só o endereço, de um domínio verificado no provedor
 * @param nomeApp    nome do aplicativo no assunto e no texto ("Servirea", "Consultoria")
 */
@ConfigurationProperties(prefix = "plataforma.auth.email")
public record EmailProperties(
        @DefaultValue("false") boolean habilitado,
        @DefaultValue("log") String provedor,
        String remetente,
        String nomeApp,
        @DefaultValue Resend resend
) {

    private static final Set<String> PROVEDORES = Set.of("log", "resend");

    /**
     * @param apiKey          chave só de envio ("Sending access") do Resend; exigida com o provedor {@code resend}
     * @param url             endpoint do envio (https; http só para localhost, nos testes)
     * @param timeoutConexao  prazo para conectar
     * @param timeoutResposta prazo para a resposta do provedor
     */
    public record Resend(String apiKey,
                         @DefaultValue("https://api.resend.com/emails") String url,
                         @DefaultValue("PT5S") Duration timeoutConexao,
                         @DefaultValue("PT10S") Duration timeoutResposta) {
        @Override
        public String toString() {
            return "Resend[url=" + url + "]";
        }
    }

    public EmailProperties {
        if (provedor == null || !PROVEDORES.contains(provedor.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("plataforma.auth.email.provedor precisa ser log ou resend.");
        }
        if (habilitado) {
            if (remetente == null || remetente.isBlank() || !remetente.contains("@") || temControle(remetente)) {
                throw new IllegalArgumentException("plataforma.auth.email.remetente é obrigatório (Nome <endereco@dominio>).");
            }
            if (nomeApp == null || nomeApp.isBlank() || temControle(nomeApp)) {
                throw new IllegalArgumentException("plataforma.auth.email.nome-app é obrigatório.");
            }
            if ("resend".equalsIgnoreCase(provedor)) {
                if (resend.apiKey() == null || resend.apiKey().isBlank()) {
                    throw new IllegalArgumentException(
                            "plataforma.auth.email.resend.api-key é obrigatório com o provedor resend (variável de ambiente, sem valor padrão).");
                }
                if (!UrlsDeLink.valida(resend.url())) {
                    throw new IllegalArgumentException("plataforma.auth.email.resend.url precisa ser https (http só para localhost).");
                }
                if (resend.timeoutConexao().isNegative() || resend.timeoutConexao().isZero()
                        || resend.timeoutResposta().isNegative() || resend.timeoutResposta().isZero()) {
                    throw new IllegalArgumentException("plataforma.auth.email.resend: os prazos precisam ser positivos.");
                }
            }
        }
    }

    boolean usaResend() {
        return "resend".equalsIgnoreCase(provedor);
    }

    private static boolean temControle(String texto) {
        return texto.chars().anyMatch(Character::isISOControl);
    }

    /** Validação de URL usada pelo e-mail e pela redefinição. */
    static final class UrlsDeLink {

        private UrlsDeLink() {
        }

        /** https, ou http só para localhost; sem espaço, sem aspas e sem caracteres de controle. */
        static boolean valida(String url) {
            if (url == null || url.isBlank() || url.length() > 2000
                    || url.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c) || c == '"' || c == '<' || c == '>')) {
                return false;
            }
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                if (host == null) {
                    return false;
                }
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    return true;
                }
                return "http".equalsIgnoreCase(uri.getScheme())
                        && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
