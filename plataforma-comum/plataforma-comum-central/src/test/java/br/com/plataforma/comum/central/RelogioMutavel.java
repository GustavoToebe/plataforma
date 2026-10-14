package br.com.plataforma.comum.central;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Relógio de teste que anda só quando mandam. */
final class RelogioMutavel extends Clock {

    private static final Instant INICIO = Instant.parse("2026-10-14T12:00:00Z");

    private volatile Instant agora = INICIO;

    void avancar(Duration duracao) {
        agora = agora.plus(duracao);
    }

    void reiniciar() {
        agora = INICIO;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
        return this;
    }

    @Override
    public Instant instant() {
        return agora;
    }
}
