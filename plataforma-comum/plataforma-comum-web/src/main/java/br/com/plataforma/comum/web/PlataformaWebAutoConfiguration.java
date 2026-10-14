package br.com.plataforma.comum.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Liga o request id, o limite de corpo e o handler global de erros em qualquer app servlet que tenha este módulo.
 * Cada bean cede a um bean do app do mesmo tipo (por exemplo, um handler que estende o {@link GlobalExceptionHandler}).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(PlataformaWebProperties.class)
public class PlataformaWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "requestIdFilter")
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registro = new FilterRegistrationBean<>(new RequestIdFilter());
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }

    @Bean
    @ConditionalOnMissingBean(name = "limiteCorpoFilter")
    @ConditionalOnProperty(prefix = "plataforma.web", name = "limite-corpo-ativo", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<LimiteCorpoFilter> limiteCorpoFilter(JsonMapper jsonMapper,
                                                                      PlataformaWebProperties propriedades) {
        FilterRegistrationBean<LimiteCorpoFilter> registro =
                new FilterRegistrationBean<>(new LimiteCorpoFilter(jsonMapper, propriedades.limiteCorpoBytes()));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registro;
    }

    @Bean
    @ConditionalOnMissingBean(GlobalExceptionHandler.class)
    public GlobalExceptionHandler globalExceptionHandler(ObjectProvider<OuvinteDeErros> ouvintes,
                                                         PlataformaWebProperties propriedades) {
        return new GlobalExceptionHandler(ouvintes.orderedStream().toList(), propriedades.prefixoCodigoProprio());
    }
}
