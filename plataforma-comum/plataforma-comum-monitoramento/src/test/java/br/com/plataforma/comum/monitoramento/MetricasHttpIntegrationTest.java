package br.com.plataforma.comum.monitoramento;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Aplicação mínima como a de um sistema: segurança com o filtro de coleta e um bloco de métricas do domínio. */
@SpringBootTest(classes = MetricasHttpIntegrationTest.AppDeTeste.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "monitoramento.token=credencial_local_de_teste_sem_uso_real_12345")
class MetricasHttpIntegrationTest {

    private static final String AUTORIZACAO = "Monitoring credencial_local_de_teste_sem_uso_real_12345";

    @Value("${local.server.port}")
    int porta;

    private HttpResponse<String> consultar(String caminho, String autorizacao) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho)).GET();
        if (autorizacao != null) {
            builder.header("Authorization", autorizacao);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void soOColetorLeOsAgregadosEAutoconfiguracaoPublicaARota() throws Exception {
        assertThat(consultar("/negocio", null).statusCode()).isEqualTo(403);
        assertThat(consultar("/monitoramento/metrics", null).statusCode()).isEqualTo(401);
        assertThat(consultar("/monitoramento/metrics", "Monitoring errado").statusCode()).isEqualTo(401);

        var resposta = consultar("/monitoramento/metrics", AUTORIZACAO);

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body())
                .contains("ecossistema_http_servidor_duracao_seconds_count", "ecossistema_jvm_memoria_bytes",
                        "ecossistema_instancias_por_nivel{nivel=\"OK\"} 7")
                .doesNotContain("uri=", "/negocio", "credencial_local");
        assertThat(resposta.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(consultar("/negocio", AUTORIZACAO).statusCode()).isEqualTo(403);
    }

    @SpringBootApplication
    @EnableMethodSecurity
    static class AppDeTeste {

        @Bean
        SecurityFilterChain cadeia(HttpSecurity http, @Value("${monitoramento.token:}") String token) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(a -> a.requestMatchers("/monitoramento/metrics").authenticated()
                            .anyRequest().denyAll())
                    .addFilterBefore(new MonitoramentoFilter(token), AnonymousAuthenticationFilter.class)
                    .build();
        }

        @Bean
        MetricasDeDominio instancias() {
            return saida -> saida.tipo("ecossistema_instancias_por_nivel", "gauge")
                    .amostra("ecossistema_instancias_por_nivel", "nivel", "OK", 7);
        }

        @RestController
        static class Negocio {
            @GetMapping("/negocio")
            String negocio() {
                return "ok";
            }
        }
    }
}
