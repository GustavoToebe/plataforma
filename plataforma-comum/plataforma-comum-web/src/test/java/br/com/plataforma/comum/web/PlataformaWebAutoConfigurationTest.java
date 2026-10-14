package br.com.plataforma.comum.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlataformaWebAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlataformaWebAutoConfiguration.class))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

    @Test
    void ligaFiltrosEHandlerPorPadraoComALeituraDasPropriedades() {
        runner.withPropertyValues("plataforma.web.limite-corpo-bytes=2048")
                .run(contexto -> {
                    assertThat(contexto).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(contexto).hasBean("requestIdFilter").hasBean("limiteCorpoFilter");
                    PlataformaWebProperties propriedades = contexto.getBean(PlataformaWebProperties.class);
                    assertThat(propriedades.limiteCorpoBytes()).isEqualTo(2048);
                    assertThat(propriedades.prefixoCodigoProprio()).isEqualTo("br.com.");
                });
    }

    @Test
    void padroesDasPropriedades() {
        runner.run(contexto -> {
            PlataformaWebProperties propriedades = contexto.getBean(PlataformaWebProperties.class);
            assertThat(propriedades.limiteCorpoAtivo()).isTrue();
            assertThat(propriedades.limiteCorpoBytes()).isEqualTo(LimiteCorpoFilter.LIMITE_PADRAO_BYTES);
        });
    }

    @Test
    void limiteDeCorpoPodeSerDesligado() {
        runner.withPropertyValues("plataforma.web.limite-corpo-ativo=false")
                .run(contexto -> assertThat(contexto)
                        .doesNotHaveBean("limiteCorpoFilter")
                        .hasBean("requestIdFilter"));
    }

    @Test
    void handlerDoSistemaTemPrioridade() {
        runner.withUserConfiguration(HandlerProprio.class).run(contexto -> {
            assertThat(contexto).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(contexto.getBean(GlobalExceptionHandler.class)).isInstanceOf(HandlerProprio.Subclasse.class);
        });
    }

    @Test
    void handlerDoSistemaComOMesmoNomeDeBeanNaoQuebraNemDuplica() {
        runner.withUserConfiguration(HandlerAntigo.class).run(contexto -> {
            assertThat(contexto).hasNotFailed();
            assertThat(contexto).getBeans(ResponseEntityExceptionHandler.class).hasSize(1);
            assertThat(contexto.getBean("globalExceptionHandler")).isInstanceOf(HandlerAntigo.Antigo.class);
        });
    }

    @Test
    void ouvintesDoSistemaChegamAoHandler() {
        runner.withUserConfiguration(OuvinteConfig.class).run(contexto -> {
            GlobalExceptionHandler handler = contexto.getBean(GlobalExceptionHandler.class);
            handler.handleApiException(new ConflictException("Repetido.", "REPETIDO"), new MockHttpServletRequest());
            assertThat(contexto.getBean(OuvinteConfig.class).chamadas).containsExactly("409:REPETIDO");
        });
    }

    @Test
    void filtroDoSistemaComOMesmoNomeSubstituiOPadrao() {
        runner.withUserConfiguration(FiltroProprio.class)
                .run(contexto -> assertThat(contexto.getBean("requestIdFilter", FilterRegistrationBean.class).getOrder())
                        .isEqualTo(42));
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerProprio {

        @Bean
        Subclasse meuHandler() {
            return new Subclasse();
        }

        static class Subclasse extends GlobalExceptionHandler {
            Subclasse() {
                super(List.of(), "br.com.");
            }
        }
    }

    /** O app que ainda não removeu o handler dele: mesmo nome de bean, classe sem relação com a do módulo. */
    @Configuration(proxyBeanMethods = false)
    static class HandlerAntigo {

        @Bean
        Antigo globalExceptionHandler() {
            return new Antigo();
        }

        static class Antigo extends ResponseEntityExceptionHandler {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OuvinteConfig {

        final List<String> chamadas = new java.util.ArrayList<>();

        @Bean
        OuvinteDeErros ouvinte() {
            return (status, codigo, mensagem, request) -> chamadas.add(status + ":" + codigo);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FiltroProprio {

        @Bean
        FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
            FilterRegistrationBean<RequestIdFilter> registro = new FilterRegistrationBean<>(new RequestIdFilter());
            registro.setOrder(42);
            return registro;
        }
    }
}
