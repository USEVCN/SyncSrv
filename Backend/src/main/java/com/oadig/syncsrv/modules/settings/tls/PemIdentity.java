package com.oadig.syncsrv.modules.settings.tls;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Validated server identity. Never expose this object (or the PEM input) through JSON or logs. */
final class PemIdentity {
  final PrivateKey key;
  final X509Certificate[] chain;
  final String certificatePem, privateKeyPem;
  private PemIdentity(PrivateKey key,X509Certificate[] chain,String cert,String pem){this.key=key;this.chain=chain;certificatePem=cert;privateKeyPem=pem;}
  static String origin(String value) {
    try {
      URI uri = URI.create(value == null ? "" : value.strip());
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
          || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
          || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
      return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT),
          uri.getPort() == 443 ? -1 : uri.getPort(), null, null, null).toString();
    } catch (Exception error) {
      throw invalid("请输入 HTTPS 对外地址，例如 https://sync.example.com");
    }
  }
  static PemIdentity parse(String cert,String pem,String origin)throws GeneralSecurityException{
    return parse(cert,pem,origin,new Date());
  }
  static PemIdentity parse(String cert,String pem,String origin,Date now)throws GeneralSecurityException{
    if(cert==null||pem==null||cert.length()>131072||pem.length()>32768)throw invalid("请提供完整的 PEM 证书链和未加密私钥");
    try{
      var matches=Pattern.compile("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----").matcher(cert);
      var list=new ArrayList<X509Certificate>();var factory=CertificateFactory.getInstance("X.509");
      while(matches.find())list.add((X509Certificate)factory.generateCertificate(new ByteArrayInputStream(Base64.getMimeDecoder().decode(matches.group(1)))));
      String residue=cert.replaceAll("-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----","").strip();
      if(list.isEmpty()||list.size()>10||!residue.isEmpty())throw invalid("证书链 PEM 格式无效");
      var chain=list.toArray(X509Certificate[]::new);String host=URI.create(origin(origin)).getHost();
      for(var c:chain)c.checkValidity(now);
      if(chain[0].getBasicConstraints()>=0)throw invalid("请导入站点证书，不能使用 CA 证书作为站点证书");
      if(!matchesHost(chain[0],host))throw invalid("证书未包含所填域名");
      var eku=chain[0].getExtendedKeyUsage();if(eku!=null&&!eku.contains("1.3.6.1.5.5.7.3.1")&&!eku.contains("2.5.29.37.0"))throw invalid("该证书不能用于 HTTPS 服务");
      boolean[] usage=chain[0].getKeyUsage();if(usage!=null&&!usage[0])throw invalid("证书不允许数字签名");
      for(int i=0;i<chain.length-1;i++){
        if(!chain[i].getIssuerX500Principal().equals(chain[i+1].getSubjectX500Principal())||chain[i+1].getBasicConstraints()<0)throw invalid("证书链顺序或签发关系无效，请提供 fullchain.pem");
        chain[i].verify(chain[i+1].getPublicKey());
      }
      if(pem.contains("ENCRYPTED")||pem.contains("Proc-Type:"))throw invalid("暂不支持加密私钥，请导入未加密 PEM 私钥");
      var m=Pattern.compile("\\s*-----BEGIN (PRIVATE KEY|RSA PRIVATE KEY|EC PRIVATE KEY)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----\\s*").matcher(pem);
      if(!m.matches())throw invalid("私钥 PEM 格式无效");byte[] der=Base64.getMimeDecoder().decode(m.group(2));
      String algorithm=chain[0].getPublicKey().getAlgorithm();
      if(m.group(1).equals("RSA PRIVATE KEY"))der=sequence(new byte[]{2,1,0},HexFormat.of().parseHex("300d06092a864886f70d0101010500"),tlv(4,der));
      if(m.group(1).equals("EC PRIVATE KEY"))der=wrapEc(der);
      if(!Set.of("RSA","EC","Ed25519","EdDSA").contains(algorithm))throw invalid("仅支持 RSA、EC 和 Ed25519 证书");
      PrivateKey key=KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
      if(key instanceof java.security.interfaces.RSAKey rsa&&rsa.getModulus().bitLength()<2048)throw invalid("RSA 私钥至少需要 2048 位");
      if(key instanceof java.security.interfaces.ECKey ec&&ec.getParams().getOrder().bitLength()<256)throw invalid("EC 私钥至少需要 256 位");
      byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
      Signature signature=Signature.getInstance(algorithm.equals("RSA")?"SHA256withRSA":algorithm.equals("EC")?"SHA256withECDSA":"Ed25519");
      signature.initSign(key);signature.update(challenge);byte[] signed=signature.sign();signature.initVerify(chain[0]);signature.update(challenge);
      if(!signature.verify(signed))throw invalid("证书与私钥不匹配");
      return new PemIdentity(key,chain,cert,pem);
    }catch(CertificateExpiredException|CertificateNotYetValidException e){throw invalid("证书已过期或尚未生效");}
    catch(IllegalArgumentException e){throw e;}
    catch(GeneralSecurityException e){throw invalid("证书或私钥校验失败，请核对证书链与私钥是否匹配");}
  }
  static boolean matchesHost(X509Certificate certificate,String host)throws CertificateParsingException{
    var names=certificate.getSubjectAlternativeNames();if(names==null)return false;
    for(var n:names)if(Integer.valueOf(7).equals(n.get(0)) && host.equalsIgnoreCase(n.get(1).toString()))return true;
    for(var n:names)if(Integer.valueOf(2).equals(n.get(0))){String dns=n.get(1).toString().toLowerCase(Locale.ROOT);if(host.equals(dns))return true;if(dns.startsWith("*.")&&host.indexOf('.')>0&&host.substring(host.indexOf('.')+1).equals(dns.substring(2)))return true;}return false;
  }
  Map<String,Object> metadata(){
    var c=chain[0];try{return Map.of("subject",c.getSubjectX500Principal().getName(),"issuer",c.getIssuerX500Principal().getName(),"notBefore",c.getNotBefore().toInstant().toString(),"notAfter",c.getNotAfter().toInstant().toString(),"daysRemaining",java.time.Duration.between(Instant.now(),c.getNotAfter().toInstant()).toDays(),"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded())),"chainLength",chain.length);}catch(GeneralSecurityException e){throw new IllegalStateException("无法读取证书信息");}
  }
  static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
  private static byte[] wrapEc(byte[] raw){
    // SEC1 parameters [0] carry the named curve OID. Convert to unencrypted PKCS#8 in memory.
    int[] p={0};byte[] body=element(raw,p,0x30);p[0]=0;element(body,p,2);element(body,p,4);byte[] params=element(body,p,0xa0);
    return sequence(new byte[]{2,1,0},sequence(HexFormat.of().parseHex("06072a8648ce3d0201"),params),tlv(4,raw));
  }
  private static byte[] element(byte[] data,int[] pos,int tag){int p=pos[0];if(p+2>data.length||(data[p++]&255)!=tag)throw invalid("EC 私钥缺少命名曲线参数");int len=data[p++]&255;if(len>127){int count=len&127;if(count==0||count>3||p+count>data.length)throw invalid("私钥格式无效");len=0;for(int i=0;i<count;i++)len=(len<<8)|(data[p++]&255);}if(len>data.length-p)throw invalid("私钥格式无效");pos[0]=p+len;return Arrays.copyOfRange(data,p,p+len);}
  private static byte[] sequence(byte[]... parts){int size=Arrays.stream(parts).mapToInt(a->a.length).sum();byte[] body=new byte[size];int p=0;for(var a:parts){System.arraycopy(a,0,body,p,a.length);p+=a.length;}return tlv(0x30,body);}
  private static byte[] tlv(int tag,byte[] raw){int count=raw.length<128?0:raw.length<256?1:raw.length<65536?2:3;byte[] out=new byte[2+count+raw.length];out[0]=(byte)tag;if(count==0)out[1]=(byte)raw.length;else{out[1]=(byte)(128+count);for(int i=0;i<count;i++)out[2+i]=(byte)(raw.length>>8*(count-i-1));}System.arraycopy(raw,0,out,2+count,raw.length);return out;}
}
