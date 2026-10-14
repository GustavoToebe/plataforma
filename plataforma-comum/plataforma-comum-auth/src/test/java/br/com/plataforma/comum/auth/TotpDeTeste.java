package br.com.plataforma.comum.auth;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Gera o código de 6 dígitos que o aplicativo autenticador mostraria (RFC 6238, SHA-1, 30 s). */
final class TotpDeTeste {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpDeTeste() {
    }

    static String codigo(String segredo, Instant agora) {
        try {
            byte[] chave = new byte[segredo.length() * 5 / 8];
            int bits = 0;
            int buffer = 0;
            int posicao = 0;
            for (int i = 0; i < segredo.length(); i++) {
                buffer = (buffer << 5) | BASE32.indexOf(segredo.charAt(i));
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    chave[posicao++] = (byte) (buffer >>> bits);
                }
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(chave, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(agora.getEpochSecond() / 30).array());
            int deslocamento = hash[hash.length - 1] & 15;
            int numero = ByteBuffer.wrap(hash, deslocamento, 4).getInt() & 0x7fffffff;
            return String.format(Locale.ROOT, "%06d", numero % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
