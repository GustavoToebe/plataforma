package br.com.plataforma.comum.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Redefinição de senha ({@code plataforma.auth.redefinicao.*}). Desligada por padrão; ligada, exige o envio de e-mail ligado
 * ({@code plataforma.auth.email.habilitado}) e a URL base do link, sem valor padrão.
 *
 * @param habilitado     liga {@link RedefinicaoDeSenha}
 * @param urlBase        página do front que recebe o link (ex.: {@code https://app.exemplo.com.br/redefinir-senha}); o módulo
 *                       acrescenta {@code #token=...}, no fragmento, que não vai para log de proxy nem para o {@code Referer}
 * @param prazo          validade do link de redefinição (padrão 30 minutos; de 5 minutos a 24 horas)
 * @param limiteIp       pedidos e confirmações por IP na janela
 * @param limiteEmail    pedidos por e-mail na janela (também impede encher a caixa de alguém)
 * @param limiteConta    confirmações por conta na janela, somando todos os links dela (trava a adivinhação do código do
 *                       MFA: pedir links novos não renova o orçamento)
 * @param janelaSegundos tamanho da janela dos limites
 */
@ConfigurationProperties(prefix = "plataforma.auth.redefinicao")
public record RedefinicaoProperties(
        @DefaultValue("false") boolean habilitado,
        String urlBase,
        @DefaultValue("PT30M") Duration prazo,
        @DefaultValue("30") int limiteIp,
        @DefaultValue("5") int limiteEmail,
        @DefaultValue("10") int limiteConta,
        @DefaultValue("3600") long janelaSegundos
) {

    public RedefinicaoProperties {
        if (habilitado) {
            if (urlBase == null || urlBase.contains("#") || !EmailProperties.UrlsDeLink.valida(urlBase)) {
                throw new IllegalArgumentException(
                        "plataforma.auth.redefinicao.url-base é obrigatório: https, sem fragmento (http só para localhost).");
            }
            if (prazo.compareTo(Duration.ofMinutes(5)) < 0 || prazo.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("plataforma.auth.redefinicao.prazo precisa ficar entre 5 minutos e 24 horas.");
            }
            if (limiteIp < 1 || limiteEmail < 1 || limiteConta < 1 || janelaSegundos < 1 || janelaSegundos > 86_400) {
                throw new IllegalArgumentException("plataforma.auth.redefinicao: limites inválidos.");
            }
        }
    }
}
