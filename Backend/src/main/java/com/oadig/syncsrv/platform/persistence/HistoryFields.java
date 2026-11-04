package com.oadig.syncsrv.platform.persistence;
import com.oadig.syncsrv.shared.Json;
/** Pure scalar extraction; returned documents are independently signature-checked. */
public final class HistoryFields {
 private HistoryFields(){}
 public static java.time.OffsetDateTime instant(String json,String field)throws Exception{
  String value=value(json,field);
  return value==null||value.isBlank()?null:java.time.Instant.parse(value).atOffset(java.time.ZoneOffset.UTC);
 }
 public static String value(String json,String field)throws Exception{
  if(json==null)return null;
  try(var parser=Json.MAPPER.createParser(json)){
   if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)return null;
   while(parser.nextToken()==com.fasterxml.jackson.core.JsonToken.FIELD_NAME){
    String name=parser.currentName();parser.nextToken();
    if(field.equals(name))return parser.getValueAsString();parser.skipChildren();
   }return null;
  }
 }
}
