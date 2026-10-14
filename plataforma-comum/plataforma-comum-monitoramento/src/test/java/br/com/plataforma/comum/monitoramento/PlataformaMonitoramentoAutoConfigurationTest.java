package br.com.plataforma.comum.monitoramento;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlataformaMonitoramentoAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlataformaMonitoramentoAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void publicaOControllerQuandoOSistemaNaoTemUm() {
        runner.run(contexto -> assertThat(contexto).hasSingleBean(MetricasController.class));
    }

    @Test
    void cedeAoMetricasControllerQueOSistemaJaTem() {
        runner.withUserConfiguration(ControllerAntigo.class).run(contexto -> {
            assertThat(contexto).hasNotFailed();
            assertThat(contexto).getBeans(MetricasController.class).hasSize(1);
            assertThat(contexto.getBean("metricasController")).isInstanceOf(ControllerAntigo.Antigo.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ControllerAntigo {

        @Bean
        Antigo metricasController(MeterRegistry registro) {
            return new Antigo(registro);
        }

        static class Antigo extends MetricasController {
            Antigo(MeterRegistry registro) {
                super(registro, List.of());
            }
        }
    }
}
