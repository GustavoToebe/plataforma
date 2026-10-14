package br.com.plataforma.comum.monitoramento;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App que adotou a dependência mas esqueceu o filtro de coleta e a segurança de método (cadeia que libera tudo): a
 * rota publicada pela autoconfiguração não pode abrir para ninguém, nem pela URL codificada que escapa do filtro.
 */
@SpringBootTest(classes = MetricasSemSegurancaIntegrationTest.AppEsquecida.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricasSemSegurancaIntegrationTest {

    @Value("${local.server.port}")
    int porta;

    @Test
    void aRotaNaoAbreParaAnonimoNemComUrlCodificada() throws Exception {
        for (String caminho : new String[] {"/monitoramento/metrics", "/monitoramento/%6Detrics"}) {
            var requisicao = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho)).GET().build();

            var resposta = HttpClient.newHttpClient().send(requisicao, HttpResponse.BodyHandlers.ofString());

            assertThat(resposta.statusCode()).as(caminho).isIn(401, 403, 404);
            assertThat(resposta.body()).as(caminho).doesNotContain("ecossistema_");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class AppEsquecida {

        @Bean
        SecurityFilterChain cadeia(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
    }
}
