package br.com.plataforma.comum.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuração do módulo web ({@code plataforma.web.*}).
 *
 * @param limiteCorpoAtivo     liga o {@link LimiteCorpoFilter} (padrão: ligado)
 * @param limiteCorpoBytes     tamanho máximo do corpo, exceto multipart (padrão: 1 MiB)
 * @param prefixoCodigoProprio prefixo dos pacotes do sistema, para o log do 500 mostrar por onde o erro passou
 *                             (padrão: {@code br.com.})
 */
@ConfigurationProperties(prefix = "plataforma.web")
public record PlataformaWebProperties(
        @DefaultValue("true") boolean limiteCorpoAtivo,
        @DefaultValue("1048576") long limiteCorpoBytes,
        @DefaultValue("br.com.") String prefixoCodigoProprio
) {
}
