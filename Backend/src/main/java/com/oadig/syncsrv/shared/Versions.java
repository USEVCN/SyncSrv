package com.oadig.syncsrv.shared;
public final class Versions {
 private Versions(){}
 public static boolean stableAtLeast(String actual,String minimum){
  if(actual==null||minimum==null||!actual.matches("[0-9]+\\.[0-9]+\\.[0-9]+")||!minimum.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))return false;
  try{String[] a=actual.split("\\."),b=minimum.split("\\.");for(int i=0;i<3;i++){long left=Long.parseLong(a[i]),right=Long.parseLong(b[i]);if(left!=right)return left>right;}return true;}catch(NumberFormatException e){return false;}
 }
}
