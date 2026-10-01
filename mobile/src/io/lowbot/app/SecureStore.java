package io.lowbot.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device-token storage. The value is encrypted with an AES-256-GCM key that
 * lives in the Android Keystore (non-exportable); only the ciphertext is kept
 * in private SharedPreferences. App backup is disabled in the manifest.
 */
final class SecureStore {
    private static final String KEY_ALIAS = "lowbot_device_key";
    private static final String PREFS = "lowbot_secure";
    private final SharedPreferences prefs;

    SecureStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    synchronized void put(String name, String value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        prefs.edit()
                .putString(name + ".iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(name + ".ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                .apply();
    }

    synchronized String get(String name) {
        String iv = prefs.getString(name + ".iv", null);
        String ct = prefs.getString(name + ".ct", null);
        if (iv == null || ct == null) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            remove(name); // key invalidated (e.g. restored device): force re-pairing
            return null;
        }
    }

    synchronized void remove(String name) {
        prefs.edit().remove(name + ".iv").remove(name + ".ct").apply();
    }
}
