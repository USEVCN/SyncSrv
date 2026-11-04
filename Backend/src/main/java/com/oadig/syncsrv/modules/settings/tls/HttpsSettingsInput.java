package com.oadig.syncsrv.modules.settings.tls;

/** Uploaded identity is write-only and must never appear in logs or responses. */
public record HttpsSettingsInput(Boolean enabled, String publicUrl, Integer port, String bindAddress,
                                 String certificatePem, String privateKeyPem) {
    @Override public String toString() { return "HTTPS settings (certificate material redacted)"; }
}
