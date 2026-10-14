package br.com.plataforma.comum.seguranca.login;

import java.util.HashMap;
import java.util.Map;

/** Contador local de um processo, com teto de chaves. Usado nos testes de unidade; em produção vale o contador do banco. */
public final class ContadorEmMemoria implements ContadorJanelas {
    private final Map<String, Estado> janelas = new HashMap<>();
    private final int maxChaves;
    private long proximaLimpeza;

    public ContadorEmMemoria(int maxChaves) {
        if (maxChaves < 2) {
            throw new IllegalArgumentException("Limites de login inválidos.");
        }
        this.maxChaves = maxChaves;
    }

    @Override
    public synchronized Estado tentar(String chave, long agora, long janelaMs) {
        if (agora >= proximaLimpeza) {
            janelas.entrySet().removeIf(e -> e.getValue().ateMs() <= agora);
            proximaLimpeza = agora + Math.min(janelaMs, 60000);
        }
        Estado atual = janelas.get(chave);
        if (atual == null || atual.ateMs() <= agora) {
            if (atual == null && janelas.size() >= maxChaves) {
                throw new LimiteLoginException(Math.max(1, (proximaLimpeza - agora + 999) / 1000));
            }
            atual = new Estado(0, agora + janelaMs);
        }
        Estado novo = new Estado(atual.tentativas() + 1, atual.ateMs());
        janelas.put(chave, novo);
        return novo;
    }
}
