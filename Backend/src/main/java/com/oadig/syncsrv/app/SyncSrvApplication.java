package com.oadig.syncsrv.app;

import com.oadig.syncsrv.modules.audit.EvidenceVerifier;
import com.oadig.syncsrv.modules.auth.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.AgentService;
import com.oadig.syncsrv.modules.jobs.TaskManager;
import com.oadig.syncsrv.modules.restore.RestoreService;
import com.oadig.syncsrv.modules.sync.SyncEngine;
import com.oadig.syncsrv.modules.transfer.TransferService;
import com.oadig.syncsrv.platform.persistence.Store;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "com.oadig.syncsrv")
public class SyncSrvApplication {
  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("verify-audit")) {
      EvidenceVerifier.run(args);
      return;
    }
    if (args.length > 0 && args[0].equals("verify-release")) {
      com.oadig.syncsrv.modules.operations.ReleaseTrust.command(args); return;
    }
    if (args.length > 0 && args[0].equals("verify-relshub-index")) {
      com.oadig.syncsrv.modules.operations.ReleaseContract.command(args); return;
    }
    if (args.length > 0 && args[0].equals("release-version")) { System.out.println(com.oadig.syncsrv.shared.BuildIdentity.version()); return; }
    if (args.length > 0 && args[0].equals("state-backup")) { com.oadig.syncsrv.modules.operations.StateBackup.command(args); return; }
    if(args.length>0&&args[0].equals("initialize-state")){com.oadig.syncsrv.modules.operations.StateSetup.command();return;}
    if(args.length>0&&args[0].equals("verify-state-files")){com.oadig.syncsrv.modules.operations.StateFiles.command();return;}
    if(args.length>0&&java.util.Set.of("backup-metadata","restore-metadata").contains(args[0])){com.oadig.syncsrv.platform.persistence.MetadataTools.run(args);return;}
    if(args.length>0&&!args[0].startsWith("--"))throw new IllegalArgumentException("未知运维命令");
    SpringApplication.run(SyncSrvApplication.class, args);
  }

  @Bean(destroyMethod = "close")
  Store store(@Value("${syncsrv.data}")String path,
      @Value("${syncsrv.production:false}")boolean production,
      @Value("${syncsrv.audit-anchor.url:}")String anchor,
      @Value("${syncsrv.audit-anchor.public-key:}")String anchorKey)throws Exception{
    if(production&&(anchor.isBlank()||anchorKey.isBlank()))throw new IllegalStateException("生产配置要求独立 HTTPS 审计锚点");
    Store opened=new Store(Path.of(path));
    if(production)try{new com.oadig.syncsrv.modules.operations.AuditAnchor(opened,anchor,anchorKey).checkpoint();}
      catch(Exception e){opened.close();throw e;}
    return opened;
  }

  @Bean
  ConfigurationService configuration(Store store) {
    return new ConfigurationService(store);
  }

  @Bean
  AgentService agents(Store store, ConfigurationService config,@Value("${syncsrv.production:false}")boolean production) {
    AgentService agents=new AgentService(store,config);if(production)agents.minimumVersion("0.16.0");return agents;
  }

  @Bean
  TransferService transfer(Store store) {
    return new TransferService(store);
  }

  @Bean
  SyncEngine sync(Store store, ConfigurationService config, TransferService transfer) {
    return new SyncEngine(store, config, transfer);
  }

  @Bean
  RestoreService restore(
      Store store, ConfigurationService config, SyncEngine sync, TransferService transfer) {
    return new RestoreService(store, config, sync, transfer);
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  TaskManager tasks(
      Store store, ConfigurationService config, SyncEngine sync, RestoreService restore)
      throws Exception {
    return new TaskManager(store, config, sync, restore);
  }

  @Bean
  AuthService auth(Store store, @Value("${SYNCSRV_INITIAL_PASSWORD:}") String password)
      throws Exception {
    return new AuthService(store, password);
  }

  @Bean
  AuthFilter authFilter(AuthService auth) {
    return new AuthFilter(auth);
  }
}
