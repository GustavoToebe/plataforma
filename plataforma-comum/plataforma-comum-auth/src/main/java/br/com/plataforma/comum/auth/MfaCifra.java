package br.com.plataforma.comum.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra os segredos TOTP (AES-256-GCM). As chaves ficam fora do banco; o dado autenticado inclui o contexto do aplicativo e o
 * sujeito, então um segredo copiado de outro usuário ou de outro sistema não decifra. Formato: {@code v1:<chave>:<nonce>:<texto>}.
 * A troca de chave é por id: a chave ativa cifra o novo, as demais continuam decifrando o que já existe.
 */
class MfaCifra {

    private final Map<String, SecretKeySpec> chaves = new HashMap<>();
    private final String ativa;
    private final String contexto;
    private final SecureRandom aleatorio = new SecureRandom();

    MfaCifra(AuthProperties.Mfa config) {
        this.ativa = config.chaveAtiva() == null ? "" : config.chaveAtiva();
        this.contexto = config.contexto();
        String lista = config.chaves() == null ? "" : config.chaves();
        if (!lista.isBlank()) {
            for (String item : lista.split(",", -1)) {
                String[] partes = item.trim().split(":", -1);
                try {
                    if (partes.length != 2 || !partes[0].matches("[A-Za-z0-9_-]{1,40}")) {
                        throw new IllegalArgumentException();
                    }
                    byte[] chave = Base64.getDecoder().decode(partes[1]);
                    if (chave.length != 32 || chaves.putIfAbsent(partes[0], new SecretKeySpec(chave, "AES")) != null) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("plataforma.auth.mfa.chaves inválida (formato id:base64 de 32 bytes, ids únicos).");
                }
            }
        }
        if ((!ativa.isBlank() || !chaves.isEmpty()) && !chaves.containsKey(ativa)) {
            throw new IllegalStateException("plataforma.auth.mfa.chave-ativa não está em plataforma.auth.mfa.chaves.");
        }
    }

    boolean configurada() {
        return chaves.containsKey(ativa);
    }

    String cifrar(String sujeito, String segredo) {
        if (!configurada()) {
            throw MfaException.indisponivel();
        }
        try {
            byte[] nonce = new byte[12];
            aleatorio.nextBytes(nonce);
            Cipher cifra = cifra(Cipher.ENCRYPT_MODE, chaves.get(ativa), nonce, sujeito);
            return "v1:" + ativa + ":" + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(cifra.doFinal(segredo.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException e) {
            throw MfaException.indisponivel();
        }
    }

    String decifrar(String sujeito, String valor) {
        try {
            String[] partes = valor.split(":", -1);
            if (partes.length != 4 || !"v1".equals(partes[0]) || !chaves.containsKey(partes[1])) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = Base64.getDecoder().decode(partes[2]);
            if (nonce.length != 12) {
                throw new IllegalArgumentException();
            }
            Cipher cifra = cifra(Cipher.DECRYPT_MODE, chaves.get(partes[1]), nonce, sujeito);
            return new String(cifra.doFinal(Base64.getDecoder().decode(partes[3])), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw MfaException.indisponivel();
        }
    }

    private Cipher cifra(int modo, SecretKeySpec chave, byte[] nonce, String sujeito) throws GeneralSecurityException {
        Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
        cifra.init(modo, chave, new GCMParameterSpec(128, nonce));
        cifra.updateAAD((contexto + ":mfa:" + sujeito).getBytes(StandardCharsets.UTF_8));
        return cifra;
    }
}
