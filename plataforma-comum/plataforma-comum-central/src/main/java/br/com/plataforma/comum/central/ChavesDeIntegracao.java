package br.com.plataforma.comum.central;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chaves de entrada do contrato (seção 2.1): {@code id:segredoBase64[,id:segredoBase64]}, no máximo duas, id no formato
 * {@code [A-Za-z0-9._-]{1,80}}, segredo com pelo menos 32 bytes. Configuração inválida derruba a subida (nada de chave ignorada
 * em silêncio, que deixaria a integração aceitando menos chaves do que o operador acha). Nunca imprime segredo.
 */
final class ChavesDeIntegracao {

    private static final int MAXIMO = 2;

    private final Map<String, byte[]> chaves;

    ChavesDeIntegracao(String configuracao) {
        Map<String, byte[]> lidas = new LinkedHashMap<>();
        if (configuracao != null && !configuracao.isBlank()) {
            for (String item : configuracao.split(",", -1)) {
                int separador = item.indexOf(':');
                if (separador < 1) {
                    throw invalida();
                }
                String id = item.substring(0, separador).trim();
                String base64 = item.substring(separador + 1).trim();
                if (!id.matches("[A-Za-z0-9._-]{1,80}")) {
                    throw invalida();
                }
                byte[] segredo;
                try {
                    segredo = Base64.getDecoder().decode(base64);
                } catch (IllegalArgumentException e) {
                    throw invalida();
                }
                if (segredo.length < 32 || lidas.putIfAbsent(id, segredo) != null) {
                    throw invalida();
                }
            }
        }
        if (lidas.size() > MAXIMO) {
            throw new IllegalStateException("plataforma.central.chaves-entrada aceita no máximo " + MAXIMO + " chaves ativas.");
        }
        this.chaves = Map.copyOf(lidas);
    }

    boolean vazia() {
        return chaves.isEmpty();
    }

    /** Segredo da chave, ou {@code null} se o id é desconhecido. */
    byte[] segredo(String id) {
        return id == null ? null : chaves.get(id.trim());
    }

    private static IllegalStateException invalida() {
        return new IllegalStateException(
                "plataforma.central.chaves-entrada inválida: use id:base64[,id:base64], id com letras, números, ponto, traço ou sublinhado, "
                        + "segredo com pelo menos 32 bytes e ids únicos.");
    }
}
