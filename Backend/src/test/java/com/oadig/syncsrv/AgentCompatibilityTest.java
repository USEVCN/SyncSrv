package com.oadig.syncsrv;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import com.oadig.syncsrv.modules.config.ConfigurationService;
import com.oadig.syncsrv.modules.discovery.AgentService;
import com.oadig.syncsrv.shared.Versions;
import org.junit.jupiter.api.Test;
class AgentCompatibilityTest {
 @Test void productionRequiresCompatibleStableAgentBeforeAssigningRoots()throws Exception{
  var memory=new MemoryStore();var d=new AgentService.Device();d.id="device";d.status="APPROVED";d.version="0.15.0-dev";memory.put("agent",d.id,d);
  var service=new AgentService(memory.store,mock(ConfigurationService.class));service.minimumVersion("0.16.0");
  var config=service.configuration(d.id);assertEquals("UPDATE_REQUIRED",config.status());assertTrue(config.roots().isEmpty());
 }
 @Test void versionComparisonIsNumericAndRejectsPrerelease(){
  assertTrue(Versions.stableAtLeast("0.16.0","0.16.0"));assertTrue(Versions.stableAtLeast("0.100.0","0.16.0"));assertFalse(Versions.stableAtLeast("0.16.0-dev","0.16.0"));assertFalse(Versions.stableAtLeast("0.9.0","0.16.0"));
 }
}
