package com.oadig.syncsrv.shared;
/** Version comes from the same generated identity checked by the release packager. */
public final class BuildIdentity {
 private BuildIdentity(){}
 public static String version(){
  try(var input=BuildIdentity.class.getResourceAsStream("/build-identity.json")){
   if(input!=null)return Json.MAPPER.readTree(input).path("center").asText("development");
  }catch(Exception e){throw new IllegalStateException("Build identity is invalid",e);}
  return "development";
 }
}
