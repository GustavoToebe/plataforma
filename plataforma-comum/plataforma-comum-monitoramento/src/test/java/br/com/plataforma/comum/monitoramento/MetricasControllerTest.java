package br.com.plataforma.comum.monitoramento;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MetricasControllerTest {

    @Test
    void agregaRequisicoesPorMetodoEClasseDeStatusSemRotaNemDadoPessoal() {
        var registro = new SimpleMeterRegistry();
        registro.timer("http.server.requests", "method", "GET", "status", "200", "uri", "/pessoas/segredo")
                .record(100, TimeUnit.MILLISECONDS);
        registro.timer("http.server.requests", "method", "GET", "status", "201", "uri", "/outra")
                .record(200, TimeUnit.MILLISECONDS);
        registro.timer("http.server.requests", "method", "TRACE", "status", "abc", "uri", "/x")
                .record(1, TimeUnit.SECONDS);

        String texto = new MetricasController(registro, List.of()).metricas().getBody();

        assertThat(texto)
                .contains("# TYPE ecossistema_http_servidor_duracao_seconds summary")
                .contains("ecossistema_http_servidor_duracao_seconds_count{metodo=\"GET\",status=\"2xx\"} 2")
                .contains("ecossistema_http_servidor_duracao_seconds_sum{metodo=\"GET\",status=\"2xx\"} 0.3")
                .contains("ecossistema_http_servidor_duracao_seconds_count{metodo=\"OUTRO\",status=\"desconhecido\"} 1")
                .contains("# TYPE ecossistema_http_cliente_duracao_seconds summary")
                .doesNotContain("pessoas", "segredo", "outra", "uri=", "TRACE");
    }

    @Test
    void exportaGaugesComNomeEstavel() {
        var registro = new SimpleMeterRegistry();
        registro.gauge("hikaricp.connections.active", new AtomicInteger(3), AtomicInteger::get);
        registro.gauge("jvm.memory.used", new AtomicInteger(1000), AtomicInteger::get);

        String texto = new MetricasController(registro, List.of()).metricas().getBody();

        assertThat(texto)
                .contains("# TYPE ecossistema_banco_conexoes_ativas gauge\necossistema_banco_conexoes_ativas 3\n")
                .contains("ecossistema_jvm_memoria_bytes 1000")
                .doesNotContain("ecossistema_banco_conexoes_ociosas");
    }

    @Test
    void metricasDeDominioVemDepoisDasComunsEUmaFalhaApenasOmiteOGrupo() {
        MetricasDeDominio instancias = saida -> saida
                .tipo("ecossistema_instancias_por_nivel", "gauge")
                .amostra("ecossistema_instancias_por_nivel", "nivel", "CRITICO", 2)
                .amostra("ecossistema_instancias_por_nivel", "nivel", "OK", 7);
        MetricasDeDominio quebrada = saida -> {
            saida.tipo("ecossistema_nao_deve_aparecer", "gauge");
            throw new IllegalStateException("banco fora");
        };
        MetricasDeDominio fila = saida -> saida
                .tipo("ecossistema_fila_envios_total", "counter")
                .amostra("ecossistema_fila_envios_total", "canal", "EMAIL", "resultado", "enviado", 5);

        var resposta = new MetricasController(new SimpleMeterRegistry(), List.of(instancias, quebrada, fila)).metricas();
        String texto = resposta.getBody();

        assertThat(texto)
                .contains("ecossistema_instancias_por_nivel{nivel=\"CRITICO\"} 2")
                .contains("ecossistema_instancias_por_nivel{nivel=\"OK\"} 7")
                .contains("ecossistema_fila_envios_total{canal=\"EMAIL\",resultado=\"enviado\"} 5")
                .doesNotContain("nao_deve_aparecer");
        assertThat(texto.indexOf("ecossistema_http_servidor_duracao_seconds"))
                .isLessThan(texto.indexOf("ecossistema_instancias_por_nivel"));
        assertThat(resposta.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void saidaFormataNumerosEEscapaRotulos() {
        String texto = new SaidaPrometheus()
                .amostra("a", 5)
                .amostra("b", 0.25)
                .amostra("c", "rotulo", "com \"aspas\" e \\ e\nquebra", 1)
                .toString();

        assertThat(texto).isEqualTo("a 5\nb 0.25\nc{rotulo=\"com \\\"aspas\\\" e \\\\ e\\nquebra\"} 1\n");
    }
}
