package br.com.plataforma.comum.monitoramento;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoramentoFilterTest {

    private static final String SEGREDO = "teste_exclusivo_de_metricas_1234567890";

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest requisicao(String metodo, String caminho, String autorizacao) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, caminho);
        request.setServletPath(caminho);
        if (autorizacao != null) {
            request.addHeader("Authorization", autorizacao);
        }
        return request;
    }

    @Test
    void aceitaSomenteAColetaDaRotaERestauraOContexto() throws Exception {
        var request = requisicao("GET", "/monitoramento/metrics", "Monitoring " + SEGREDO);
        var response = new MockHttpServletResponse();

        new MonitoramentoFilter(SEGREDO).doFilter(request, response, (req, res) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                        .extracting(Object::toString).containsExactly("ROLE_MONITORAMENTO"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void semConfiguracaoOuComTokenForaDoFormatoNaoColeta() throws Exception {
        for (String configurado : new String[] {"", null, "curto", "com espaço no meio 1234567890123456789012"}) {
            var response = new MockHttpServletResponse();

            new MonitoramentoFilter(configurado).doFilter(
                    requisicao("GET", "/monitoramento/metrics", "Monitoring " + SEGREDO), response, (req, res) -> {
                        throw new AssertionError("não deve encaminhar");
                    });

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Test
    void tokenErradoOuGrandeDemaisEhRecusado() throws Exception {
        for (String enviado : new String[] {
            "Bearer qualquer", "Monitoring outro_token_de_32_caracteres_ou_mais_123", "Monitoring " + "a".repeat(129)}) {
            var response = new MockHttpServletResponse();

            new MonitoramentoFilter(SEGREDO).doFilter(requisicao("GET", "/monitoramento/metrics", enviado), response,
                    (req, res) -> {
                        throw new AssertionError("não deve encaminhar");
                    });

            assertThat(response.getStatus()).isEqualTo(401);
        }
    }

    @Test
    void tokenDeColetaNaoAutenticaNegocio() throws Exception {
        var request = requisicao("GET", "/clientes", "Monitoring " + SEGREDO);

        new MonitoramentoFilter(SEGREDO).doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());
    }

    @Test
    void postNaoEhColeta() throws Exception {
        var response = new MockHttpServletResponse();

        new MonitoramentoFilter(SEGREDO).doFilter(
                requisicao("POST", "/monitoramento/metrics", "Monitoring " + SEGREDO), response, (req, res) -> {
                    throw new AssertionError();
                });

        assertThat(response.getStatus()).isEqualTo(401);
    }
}
