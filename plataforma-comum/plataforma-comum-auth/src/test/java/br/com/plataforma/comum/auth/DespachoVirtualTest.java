package br.com.plataforma.comum.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DespachoVirtualTest {

    /** O despacho de verdade (os testes de integração trocam por um que roda na hora): sai da thread do chamador, que não espera. */
    @Test
    void rodaNumaThreadVirtualSemPrenderOChamador() throws Exception {
        CompletableFuture<Thread> rodou = new CompletableFuture<>();
        CompletableFuture<Void> soltar = new CompletableFuture<>();

        new DespachoVirtual().despachar(() -> {
            rodou.complete(Thread.currentThread());
            soltar.join();
        });

        // O chamador já voltou enquanto a tarefa ainda está presa: não esperou por ela.
        Thread thread = rodou.get(10, TimeUnit.SECONDS);
        assertThat(soltar).isNotDone();
        assertThat(thread.isVirtual()).isTrue();
        assertThat(thread).isNotSameAs(Thread.currentThread());
        soltar.complete(null);
    }
}
