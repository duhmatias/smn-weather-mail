package ar.gob.smn.weather;

import java.security.GeneralSecurityException;

/**
 * Prints an {@code ENC1:...} string for {@code mail.smtp.password}. Usage:
 * <pre>
 *   SMN_MASTER_PASSWORD='your-passphrase' java -cp "target/smn-weather-mail-1.0.0.jar:target/lib/*" \
 *     ar.gob.smn.weather.ConfigCryptoCli encrypt 'plaintext-smtp-key'
 * </pre>
 */
public final class ConfigCryptoCli {

    public static void main(String[] args) throws GeneralSecurityException {
        if (args.length != 2 || !"encrypt".equals(args[0])) {
            System.err.println("Usage: SMN_MASTER_PASSWORD=<passphrase> java ... ConfigCryptoCli encrypt <plaintext>");
            System.exit(1);
        }
        String master = System.getenv("SMN_MASTER_PASSWORD");
        if (master == null || master.isBlank()) {
            System.err.println("Set SMN_MASTER_PASSWORD to the passphrase used to protect the property.");
            System.exit(1);
        }
        System.out.println(PropertySecretCipher.encrypt(args[1], master));
    }
}
