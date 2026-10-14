package br.com.plataforma.comum.central;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * Os vetores da seção 2.5 do contrato, com valores escritos à mão (não gerados pelo código testado): se o texto assinado do filtro
 * (método, caminho com query, timestamp, nonce, hash do corpo) divergir do contrato, estes testes quebram. Sem banco: o nonce é um
 * stub.
 */
class FiltroContratoTest {

    private static final String SEGREDO_BASE64 = Base64.getEncoder()
            .encodeToString("segredo-de-teste-nao-usar-em-producao-0123456789".getBytes(StandardCharsets.UTF_8));

    private static final String CORPO_VETOR_1 = "{\"contratacaoId\":\"0f8e2c1a-1111-4a2b-9c3d-000000000001\"}";

    /** Nonce em memória: só o suficiente para o filtro. */
    private static final class NonceEmMemoria extends NonceStore {
        final List<String> vistos = new ArrayList<>();

        NonceEmMemoria() {
            super(null, null, Clock.systemUTC());
        }

        @Override
        boolean registrar(String chaveId, String nonce) {
            return vistos.add(chaveId + "/" + nonce);
        }
    }

    private static IntegracaoFiltro filtro(long agoraEpoch, NonceStore nonces) {
        CentralProperties propriedades = new CentralProperties(true, null, null, null, null, "/integracao/", 72,
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(10), 1 << 20, 1 << 20,
                new CentralProperties.Migracao(null, null, null, null));
        return new IntegracaoFiltro(propriedades, new ChavesDeIntegracao("central-servire-2026a:" + SEGREDO_BASE64), nonces,
                Clock.fixed(Instant.ofEpochSecond(agoraEpoch), ZoneOffset.UTC), JsonMapper.builder().build());
    }

    private static MockHttpServletRequest pedido(String metodo, String caminho, String timestamp, String nonce, String assinatura, String corpo) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, caminho);
        request.setServletPath(caminho);
        request.addHeader(IntegracaoFiltro.H_CHAVE, "central-servire-2026a");
        request.addHeader(IntegracaoFiltro.H_TIMESTAMP, timestamp);
        request.addHeader(IntegracaoFiltro.H_NONCE, nonce);
        request.addHeader(IntegracaoFiltro.H_ASSINATURA, assinatura);
        if (corpo != null) {
            request.setContent(corpo.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    @Test
    void vetor1PostComCorpoPassaEOCorpoChegaIntacto() throws Exception {
        NonceEmMemoria nonces = new NonceEmMemoria();
        MockHttpServletRequest request = pedido("POST", "/integracao/v1/instancias", "1790000000", "5b1f2a0e-8c3d-4f6a-9b7e-1d2c3b4a5f60",
                "v1=4c63e60805c925d7c3cd321c8726e984ff1202ba6e62df81c6f5749694f99f32", CORPO_VETOR_1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain cadeia = new MockFilterChain();

        filtro(1790000000L, nonces).doFilter(request, response, cadeia);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(cadeia.getRequest()).as("a cadeia foi alcançada").isNotNull();
        assertThat(new String(cadeia.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(CORPO_VETOR_1);
        assertThat(nonces.vistos).containsExactly("central-servire-2026a/5b1f2a0e-8c3d-4f6a-9b7e-1d2c3b4a5f60");
    }

    @Test
    void vetor2GetSemCorpoNoLimiteDaJanela() throws Exception {
        NonceEmMemoria nonces = new NonceEmMemoria();
        MockHttpServletRequest request = pedido("GET", "/integracao/v1/produtos/SERVIRE/direitos", "1790000300",
                "a7c9e1f2-2222-4b3c-8d4e-000000000002",
                "v1=976359d02a3d078c41896cff976ba58fcb6cad78060392fbc43b676a95f8b113", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain cadeia = new MockFilterChain();

        // Relógio do receptor em 1790000000: o timestamp está exatamente 300 s à frente, ainda dentro da janela.
        filtro(1790000000L, nonces).doFilter(request, response, cadeia);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(cadeia.getRequest()).isNotNull();
    }

    @Test
    void qualquerAlteracaoNoVetor1QuebraAAssinatura() throws Exception {
        String assinatura = "v1=4c63e60805c925d7c3cd321c8726e984ff1202ba6e62df81c6f5749694f99f32";
        String nonce = "5b1f2a0e-8c3d-4f6a-9b7e-1d2c3b4a5f60";
        MockHttpServletRequest[] alterados = {
                pedido("PUT", "/integracao/v1/instancias", "1790000000", nonce, assinatura, CORPO_VETOR_1),
                pedido("POST", "/integracao/v1/instancias/", "1790000000", nonce, assinatura, CORPO_VETOR_1),
                pedido("POST", "/integracao/v1/instancias", "1790000001", nonce, assinatura, CORPO_VETOR_1),
                pedido("POST", "/integracao/v1/instancias", "1790000000", nonce + "x", assinatura, CORPO_VETOR_1),
                pedido("POST", "/integracao/v1/instancias", "1790000000", nonce, assinatura, CORPO_VETOR_1 + " "),
        };
        for (MockHttpServletRequest request : alterados) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain cadeia = new MockFilterChain();
            filtro(1790000000L, new NonceEmMemoria()).doFilter(request, response, cadeia);
            assertThat(response.getStatus()).as("%s %s", request.getMethod(), request.getRequestURI()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("ASSINATURA_INVALIDA");
            assertThat(cadeia.getRequest()).isNull();
        }
    }

    @Test
    void queryStringEntraNoTextoAssinadoComoEnviada() throws Exception {
        // Mesma assinatura do vetor 1, mas com query string: o caminho assinado deixa de ser o do vetor.
        MockHttpServletRequest request = pedido("POST", "/integracao/v1/instancias", "1790000000", "5b1f2a0e-8c3d-4f6a-9b7e-1d2c3b4a5f60",
                "v1=4c63e60805c925d7c3cd321c8726e984ff1202ba6e62df81c6f5749694f99f32", CORPO_VETOR_1);
        request.setQueryString("a=1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain cadeia = new MockFilterChain();

        filtro(1790000000L, new NonceEmMemoria()).doFilter(request, response, cadeia);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(cadeia.getRequest()).isNull();
    }
}
