package br.com.plataforma.comum.monitoramento;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Publica {@code GET /monitoramento/metrics} em qualquer app servlet (o actuator traz o Micrometer). Cede a um {@code metricasController}
 * do próprio sistema, para a adoção ser gradual. A cadeia de segurança do sistema precisa registrar o
 * {@link MonitoramentoFilter} (com {@code monitoramento.token}) antes do filtro de JWT; sem isso a rota responde 401/403.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlataformaMonitoramentoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "metricasController")
    public MetricasController plataformaMetricasController(MeterRegistry registro,
                                                           ObjectProvider<MetricasDeDominio> dominio) {
        return new MetricasController(registro, dominio.orderedStream().toList());
    }
}
