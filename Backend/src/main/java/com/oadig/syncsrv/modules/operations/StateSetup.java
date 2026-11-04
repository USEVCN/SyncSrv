package com.oadig.syncsrv.modules.operations;
import com.oadig.syncsrv.modules.auth.AuthService;
import com.oadig.syncsrv.platform.persistence.Store;
import java.nio.file.Path;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
/** Creates signing identity before the independent anchor is paired; never opens a web port. */
public final class StateSetup {
 private StateSetup(){}
 public static void command()throws Exception{
  var env=new StandardEnvironment();ConfigDataEnvironmentPostProcessor.applyTo(env);
  try(Store store=new Store(Path.of(env.getRequiredProperty("syncsrv.data")))){
   new AuthService(store,env.getProperty("SYNCSRV_INITIAL_PASSWORD",""));
   System.out.println(store.publicKey());
  }
 }
}
