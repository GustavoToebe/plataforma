package br.com.plataforma.comum.seguranca;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class TotpTest {
    private static final String SEGREDO = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    @Test void vetoresOficiaisRfc6238Sha1IncluindoTempoAlemDe2038() {
        long[] tempos = {59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000L};
        String[] esperado = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i = 0; i < tempos.length; i++) assertThat(Totp.codigo(SEGREDO, tempos[i] / 30, 8)).isEqualTo(esperado[i]);
    }
    @Test void janelaRestritaEPassoConsumidoNaoPodeSerReutilizado() {
        Instant agora = Instant.ofEpochSecond(3000);
        assertThat(Totp.verificar(SEGREDO, Totp.codigo(SEGREDO, 100, 6), agora, -1)).isEqualTo(100);
        assertThat(Totp.verificar(SEGREDO, Totp.codigo(SEGREDO, 99, 6), agora, -1)).isEqualTo(99);
        assertThat(Totp.verificar(SEGREDO, Totp.codigo(SEGREDO, 101, 6), agora, -1)).isEqualTo(101);
        assertThat(Totp.verificar(SEGREDO, Totp.codigo(SEGREDO, 98, 6), agora, -1)).isEqualTo(-1);
        assertThat(Totp.verificar(SEGREDO, Totp.codigo(SEGREDO, 100, 6), agora, 100)).isEqualTo(-1);
        assertThat(Totp.verificar(SEGREDO, null, agora, -1)).isEqualTo(-1);
        assertThat(Totp.verificar(SEGREDO, "1234567", agora, -1)).isEqualTo(-1);
    }
    @Test void segredosAleatoriosDe160BitsEmBase32() {
        String a = Totp.novoSegredo(), b = Totp.novoSegredo();
        assertThat(a).matches("[A-Z2-7]{32}").isNotEqualTo(b);
    }
}
