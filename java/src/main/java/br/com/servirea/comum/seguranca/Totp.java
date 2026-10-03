package br.com.servirea.comum.seguranca;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/** RFC 6238: segredo aleatório exclusivo, SHA-1, seis dígitos e passos de 30 segundos. */
public final class Totp {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private Totp() {}

    public static String novoSegredo() {
        byte[] bytes = new byte[20]; RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        int bits = 0, buffer = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 255); bits += 8;
            while (bits >= 5) {bits -= 5; out.append(BASE32.charAt((buffer >>> bits) & 31));}
        }
        return out.toString();
    }

    public static long verificar(String segredo, String codigo, Instant agora, long ultimoPasso) {
        if (codigo == null || !codigo.matches("[0-9]{6}")) return -1;
        long passo = agora.getEpochSecond() / 30;
        // Preferir o passo atual; cada código aceito consome também os anteriores.
        for (long candidato : new long[]{passo, passo - 1, passo + 1}) {
            if (candidato > ultimoPasso && candidato >= 0 && MessageDigest.isEqual(
                    codigo.getBytes(StandardCharsets.US_ASCII), codigo(segredo, candidato, 6).getBytes(StandardCharsets.US_ASCII))) return candidato;
        }
        return -1;
    }

    static String codigo(String segredo, long passo, int digitos) {
        try {
            byte[] key = new byte[segredo.length() * 5 / 8]; int bits = 0, buffer = 0, pos = 0;
            for (char c : segredo.toCharArray()) {
                int valor = BASE32.indexOf(c);
                if (valor < 0) throw new IllegalArgumentException("Segredo TOTP inválido.");
                buffer = (buffer << 5) | valor; bits += 5;
                if (bits >= 8) {bits -= 8; key[pos++] = (byte)(buffer >>> bits);}
            }
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(passo).array());
            int offset = hash[hash.length - 1] & 15;
            int numero = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
            int modulo = digitos == 8 ? 100_000_000 : 1_000_000;
            return String.format(java.util.Locale.ROOT, "%0" + digitos + "d", numero % modulo);
        } catch (java.security.GeneralSecurityException ex) {throw new IllegalStateException("TOTP indisponível.", ex);}
    }
}
