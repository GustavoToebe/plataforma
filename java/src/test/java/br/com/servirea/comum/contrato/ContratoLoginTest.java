package br.com.servirea.comum.contrato;

import br.com.servirea.comum.login.ContadorJanelas;
import br.com.servirea.comum.login.LimiteLogin;
import br.com.servirea.comum.login.LimiteLoginException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class ContratoLoginTest {
    @Test void contadorExternoRecebeChavesAnonimizadasEDevolvePrazoDeBloqueio() {
        var chaves = new ArrayList<String>();
        ContadorJanelas contador = (chave, agora, janela) -> {
            chaves.add(chave);
            return new ContadorJanelas.Estado(3, agora + janela);
        };
        var limite = new LimiteLogin(2, 2, 60, contador,
                Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC), "sal-exclusivo-de-teste");
        assertThatThrownBy(() -> limite.registrar("192.0.2.1", " Ana@exemplo.test "))
                .isInstanceOfSatisfying(LimiteLoginException.class, erro -> {
                    assertThat(erro.segundos()).isEqualTo(60);
                    assertThat(erro.getCodigo()).isEqualTo("LOGIN_LIMITADO");
                });
        assertThatThrownBy(() -> limite.registrar("192.0.2.1", "ANA@EXEMPLO.TEST"))
                .isInstanceOf(LimiteLoginException.class);
        assertThat(chaves.get(0)).matches("ip:[0-9a-f]{64}").isEqualTo(chaves.get(2));
        assertThat(chaves.get(1)).matches("conta:[0-9a-f]{64}").isEqualTo(chaves.get(3));
    }
}
