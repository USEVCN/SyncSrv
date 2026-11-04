package com.oadig.syncsrv.shared.web;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import org.junit.jupiter.api.Test;
class SafeMessagesTest {
  @Test void credentialsAreRemovedBeforeTruncation() {
    String result=SafeMessages.text("SFTP https://user:fixture-secret@host/path?token=fixture-token password='fixture-password' Authorization: Bearer fixture-bearer");
    for(String value:new String[]{"fixture-secret","fixture-token","fixture-password","fixture-bearer"})assertFalse(result.contains(value),result);
    assertTrue(result.contains("host/path"));
  }
  @Test void completePrivateKeysAndCookieHeadersAreRemoved() {
    assertFalse(SafeMessages.text("-----BEGIN PRIVATE KEY-----\nfixture-key-material\n-----END PRIVATE KEY-----").contains("fixture-key-material"));
    assertFalse(SafeMessages.text("Cookie: session=fixture-session; csrf=fixture-csrf").contains("fixture-"));
  }
  @Test void exceptionCausesAndSuppressedErrorsAreAlsoRedacted() {
    Exception error=new IOException("password=fixture-top",new IOException("secret=fixture-cause"));
    error.addSuppressed(new IOException("token=fixture-suppressed"));
    StringWriter buffer=new StringWriter();SafeMessages.logged(error).printStackTrace(new PrintWriter(buffer));
    assertFalse(buffer.toString().contains("fixture-"));assertTrue(buffer.toString().contains("IOException"));
  }
}
