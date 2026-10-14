package br.com.plataforma.comum.seguranca;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HmacAssinaturaTest {

    private static final byte[] SEGREDO =
            "segredo-de-teste-nao-usar-em-producao-0123456789".getBytes(StandardCharsets.UTF_8);

    @Test
    void vetorPostComCorpo() {
        byte[] corpo = "{\"contratacaoId\":\"0f8e2c1a-1111-4a2b-9c3d-000000000001\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(HmacAssinatura.sha256Hex(corpo))
                .isEqualTo("c1289f0c4d760055dd35521a90bdb3fbd73807fbeff733378963662a77a34223");
        assertThat(HmacAssinatura.assinar(SEGREDO, "POST", "/integracao/v1/instancias",
                "1790000000", "5b1f2a0e-8c3d-4f6a-9b7e-1d2c3b4a5f60", corpo))
                .isEqualTo("v1=4c63e60805c925d7c3cd321c8726e984ff1202ba6e62df81c6f5749694f99f32");
    }

    @Test
    void vetorGetSemCorpo() {
        byte[] corpo = new byte[0];
        assertThat(HmacAssinatura.sha256Hex(corpo))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(HmacAssinatura.assinar(SEGREDO, "GET", "/integracao/v1/produtos/SERVIRE/direitos",
                "1790000300", "a7c9e1f2-2222-4b3c-8d4e-000000000002", corpo))
                .isEqualTo("v1=976359d02a3d078c41896cff976ba58fcb6cad78060392fbc43b676a95f8b113");
    }
}
