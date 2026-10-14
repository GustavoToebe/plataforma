package br.com.plataforma.comum.monitoramento;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Exportação restrita: agregados operacionais, sem URI, paróquia, usuário ou conteúdo. Requisições e chamadas de saída
 * viram duração por método e classe de status ({@code 2xx}); depois vêm JVM, processo e pool de conexões, e por fim as
 * métricas do domínio ({@link MetricasDeDominio}).
 */
@RestController
public class MetricasController {

    private static final Logger log = LoggerFactory.getLogger(MetricasController.class);

    private static final Set<String> METODOS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Pattern STATUS = Pattern.compile("[1-5][0-9]{2}");

    /** Métrica do Micrometer -> nome exportado. */
    private static final Map<String, String> GAUGES = Map.of(
            "jvm.memory.used", "ecossistema_jvm_memoria_bytes",
            "process.uptime", "ecossistema_process_uptime_segundos",
            "process.cpu.usage", "ecossistema_process_cpu_fracao",
            "hikaricp.connections.active", "ecossistema_banco_conexoes_ativas",
            "hikaricp.connections.idle", "ecossistema_banco_conexoes_ociosas",
            "hikaricp.connections.pending", "ecossistema_banco_conexoes_pendentes",
            "hikaricp.connections.max", "ecossistema_banco_conexoes_maximas");

    private final MeterRegistry registro;
    private final List<MetricasDeDominio> dominio;

    public MetricasController(MeterRegistry registro, List<MetricasDeDominio> dominio) {
        this.registro = registro;
        this.dominio = List.copyOf(dominio);
    }

    @GetMapping(value = MonitoramentoFilter.CAMINHO, produces = "text/plain; version=0.0.4; charset=utf-8")
    @PreAuthorize("hasRole('" + MonitoramentoFilter.PAPEL + "')")
    public ResponseEntity<String> metricas() {
        // Defesa em profundidade: a rota é publicada só por ter a dependência. Sem @EnableMethodSecurity o
        // @PreAuthorize não age, e uma URL codificada (/monitoramento/%6Detrics) escapa do filtro de coleta.
        if (!coletorAutenticado()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).header("Cache-Control", "no-store").build();
        }
        SaidaPrometheus saida = new SaidaPrometheus();
        duracoes(saida, "http.server.requests", "ecossistema_http_servidor");
        duracoes(saida, "http.client.requests", "ecossistema_http_cliente");
        GAUGES.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> gauge(saida, e.getKey(), e.getValue()));
        for (MetricasDeDominio grupo : dominio) {
            SaidaPrometheus parcial = new SaidaPrometheus();
            try {
                grupo.escrever(parcial);
                saida.bruto(parcial.toString());
            } catch (RuntimeException falha) {
                // Métricas de processo continuam disponíveis mesmo se o banco estiver lento.
                log.warn("Métricas de domínio omitidas: {}", falha.getClass().getName());
            }
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(saida.toString());
    }

    private static boolean coletorAutenticado() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        return autenticacao != null && autenticacao.getAuthorities().stream()
                .anyMatch(papel -> ("ROLE_" + MonitoramentoFilter.PAPEL).equals(papel.getAuthority()));
    }

    private void gauge(SaidaPrometheus saida, String origem, String nome) {
        Collection<Gauge> medidores = registro.find(origem).gauges();
        if (medidores.isEmpty()) {
            return;
        }
        double soma = medidores.stream().mapToDouble(Gauge::value).sum();
        if (Double.isFinite(soma)) {
            saida.tipo(nome, "gauge").amostra(nome, soma);
        }
    }

    private void duracoes(SaidaPrometheus saida, String origem, String nome) {
        Map<String, double[]> grupos = new TreeMap<>();
        for (Timer timer : registro.find(origem).timers()) {
            String metodo = timer.getId().getTag("method");
            String status = timer.getId().getTag("status");
            if (metodo == null || !METODOS.contains(metodo)) {
                metodo = "OUTRO";
            }
            String classe = status != null && STATUS.matcher(status).matches() ? status.charAt(0) + "xx" : "desconhecido";
            String chave = metodo + " " + classe;
            double[] valores = grupos.computeIfAbsent(chave, k -> new double[2]);
            valores[0] += timer.count();
            valores[1] += timer.totalTime(TimeUnit.SECONDS);
        }
        String duracao = nome + "_duracao_seconds";
        saida.tipo(duracao, "summary");
        grupos.forEach((chave, valores) -> {
            String[] partes = chave.split(" ");
            saida.amostra(duracao + "_count", "metodo", partes[0], "status", partes[1], (long) valores[0]);
            saida.amostra(duracao + "_sum", "metodo", partes[0], "status", partes[1], valores[1]);
        });
    }
}
