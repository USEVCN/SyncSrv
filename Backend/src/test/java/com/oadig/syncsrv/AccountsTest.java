package com.oadig.syncsrv;
import static org.junit.jupiter.api.Assertions.*;
import com.oadig.syncsrv.modules.auth.*;
import com.oadig.syncsrv.modules.transfer.BandwidthLimiter;
import org.junit.jupiter.api.Test;
class AccountsTest {
 @Test void namedRolesRevocationAndLastAdministratorProtection()throws Exception{
  var memory=new MemoryStore();var auth=new AuthService(memory.store,"fixture-password-2026");
  auth.saveAccount("auditor","auditor-password-2026","AUDITOR",false,"admin");
  String token=auth.login("auditor","auditor-password-2026","loopback");
  assertEquals("auditor",auth.actor(token));assertEquals("AUDITOR",auth.role("auditor"));
  assertThrows(IllegalArgumentException.class,()->auth.saveAccount("admin",null,"OPERATOR",false,"admin"));
  assertThrows(IllegalArgumentException.class,()->auth.saveAccount("bad","long-password-here","ADMIN",false,"auditor"));
  auth.saveAccount("auditor",null,"AUDITOR",true,"admin");assertNull(auth.actor(token));
  assertThrows(IllegalArgumentException.class,()->auth.login("auditor","auditor-password-2026","loopback"));
 }
 @Test void centerAndFleetReservationsShareOneBudget(){
  var limiter=new BandwidthLimiter(1);
  long one=limiter.reserve(1024*1024),two=limiter.reserve(1024*1024);
  assertTrue(one>=700&&one<=800,"initial bounded burst");assertTrue(two>=one+900,"multiple callers cannot multiply global allowance");
  limiter.set(0);assertEquals(0,limiter.reserve(1024));
  assertThrows(IllegalArgumentException.class,()->limiter.reserve(16*1024*1024));
 }
}
