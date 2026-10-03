package br.com.servirea.comum.login;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class LimiteLoginTest {
    private static class Relogio extends Clock {
        long tempo;
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(tempo);}
    }
    @Test void contaNormalizadaLimitaMesmoMudandoIpEReabreAposJanela(){
        var clock=new Relogio();var limite=new LimiteLogin(20,2,60,100,clock);
        limite.registrar("ip1"," Ana@teste.com ");limite.registrar("ip2","ana@teste.com");
        assertThatThrownBy(() -> limite.registrar("ip3","ANA@TESTE.COM")).isInstanceOf(LimiteLoginException.class);
        clock.tempo=60000;assertThatCode(() -> limite.registrar("ip3","ana@teste.com")).doesNotThrowAnyException();
    }
    @Test void concorrenciaNaoUltrapassaLimiteDoMesmoIp() throws Exception {
        var limite=new LimiteLogin(5,100,60,100,Clock.systemUTC());var aceitas=new AtomicInteger();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tarefas=new java.util.ArrayList<Future<?>>();
            for(int n=0;n<30;n++){final int indice=n;tarefas.add(pool.submit(() -> {try{limite.registrar("mesmo-ip","a"+indice+"@teste.com");aceitas.incrementAndGet();}catch(LimiteLoginException esperado){}}));}
            for(var f:tarefas)f.get(5,TimeUnit.SECONDS);
        }
        assertThat(aceitas.get()).isEqualTo(5);
    }
    @Test void saturacaoNaoExpulsaContadorAtivoEReabreAposLimpeza(){
        var clock=new Relogio();var limite=new LimiteLogin(1,1,60,2,clock);limite.registrar("a","a@teste.com");
        assertThatThrownBy(() -> limite.registrar("b","b@teste.com")).isInstanceOf(LimiteLoginException.class);
        assertThatThrownBy(() -> limite.registrar("a","a@teste.com")).isInstanceOf(LimiteLoginException.class);
        clock.tempo=60000;assertThatCode(() -> limite.registrar("b","b@teste.com")).doesNotThrowAnyException();
    }
}
