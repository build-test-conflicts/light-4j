package com.networknt.security;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.client.oauth.KeyRequest;
import com.networknt.client.oauth.OauthHelper;
import com.networknt.config.Config;
import com.networknt.exception.ExpiredTokenException;
import com.networknt.utility.FingerPrintUtil;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.*;
import org.jose4j.jwx.JsonWebStructure;
import org.jose4j.keys.resolvers.X509VerificationKeyResolver;
import org.owasp.encoder.Encode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
/** 
 * JWT token helper utility that use by different framework to verify JWT tokens.
 * @author Steve Hu
 */
public class JwtHelper {
  static Logger logger=LoggerFactory.getLogger(JwtHelper.class);
  public static String KID="kid";
  public static String JWT_CONFIG="jwt";
  public static String SECURITY_CONFIG="security";
  public static String JWT_CERTIFICATE="certificate";
  public static String JWT_CLOCK_SKEW_IN_SECONDS="clockSkewInSeconds";
  public static String ENABLE_VERIFY_JWT="enableVerifyJwt";
  private static String ENABLE_JWT_CACHE="enableJwtCache";
  private static String BOOTSTRAP_FROM_KEY_SERVICE="bootstrapFromKeyService";
  private static int CACHE_EXPIRED_IN_MINUTES=15;
  static Map<String,X509Certificate> certMap;
  static List<String> fingerPrints;
  static Map<String,Object> securityConfig=(Map)Config.getInstance().getJsonMapConfig(SECURITY_CONFIG);
  static Map<String,Object> securityJwtConfig=(Map)securityConfig.get(JWT_CONFIG);
  static int secondsOfAllowedClockSkew=(Integer)securityJwtConfig.get(JWT_CLOCK_SKEW_IN_SECONDS);
  static Boolean enableJwtCache=(Boolean)securityConfig.get(ENABLE_JWT_CACHE);
  static Boolean bootstrapFromKeyService=(Boolean)securityConfig.get(BOOTSTRAP_FROM_KEY_SERVICE);
  static Cache<String,JwtClaims> cache;
static {
    if (Boolean.TRUE.equals(enableJwtCache)) {
      cache=Caffeine.newBuilder().expireAfterWrite(CACHE_EXPIRED_IN_MINUTES,TimeUnit.MINUTES).build();
    }
  }
  /** 
 * Read certificate from a file and convert it into X509Certificate object
 * @param filename certificate file name
 * @return X509Certificate object
 * @throws Exception Exception while reading certificate
 */
  public static public X509Certificate readCertificate(  String filename) throws Exception {
    InputStream inStream=null;
    X509Certificate cert=null;
    try {
      inStream=Config.getInstance().getInputStreamFromFile(filename);
      if (inStream != null) {
        CertificateFactory cf=CertificateFactory.getInstance("X.509");
        cert=(X509Certificate)cf.generateCertificate(inStream);
      }
 else {
        logger.info("Certificate " + Encode.forJava(filename) + " not found.");
      }
    }
 catch (    Exception e) {
      logger.error("Exception: ",e);
    }
 finally {
      if (inStream != null) {
        try {
          inStream.close();
        }
 catch (        IOException ioe) {
          logger.error("Exception: ",ioe);
        }
      }
    }
    return cert;
  }
static {
    if (bootstrapFromKeyService == null || Boolean.FALSE.equals(bootstrapFromKeyService)) {
      certMap=new HashMap<>();
      fingerPrints=new ArrayList<>();
      Map<String,Object> keyMap=(Map<String,Object>)securityJwtConfig.get(JwtHelper.JWT_CERTIFICATE);
      for (      String kid : keyMap.keySet()) {
        X509Certificate cert=null;
        try {
          cert=JwtHelper.readCertificate((String)keyMap.get(kid));
        }
 catch (        Exception e) {
          logger.error("Exception:",e);
        }
        certMap.put(kid,cert);
        fingerPrints.add(FingerPrintUtil.getCertFingerPrint(cert));
      }
    }
  }
  /** 
 * Parse the jwt token from Authorization header.
 * @param authorization authorization header.
 * @return JWT token
 */
  public static String getJwtFromAuthorization(  String authorization){
    String jwt=null;
    if (authorization != null) {
      String[] parts=authorization.split(" ");
      if (parts.length == 2) {
        String scheme=parts[0];
        String credentials=parts[1];
        Pattern pattern=Pattern.compile("^Bearer$",Pattern.CASE_INSENSITIVE);
        if (pattern.matcher(scheme).matches()) {
          jwt=credentials;
        }
      }
    }
    return jwt;
  }
  /** 
 * Verify JWT token format and signature. If ignoreExpiry is true, skip expiry verification, otherwise verify the expiry before signature verification. In most cases, we need to verify the expiry of the jwt token. The only time we need to ignore expiry verification is in SPA middleware handlers which need to verify csrf token in jwt against the csrf token in the request header to renew the expired token.
 * @param jwt String of Json web token
 * @param ignoreExpiry If true, don't verify if the token is expired.
 * @return JwtClaims object
 * @throws InvalidJwtException InvalidJwtException
 * @throws ExpiredTokenException ExpiredTokenException
 */
  public static JwtClaims verifyJwt(  String jwt,  boolean ignoreExpiry) throws InvalidJwtException, ExpiredTokenException {
    JwtClaims claims;
    if (Boolean.TRUE.equals(enableJwtCache)) {
      claims=cache.getIfPresent(jwt);
      if (claims != null) {
        if (!ignoreExpiry) {
          try {
            if ((NumericDate.now().getValue() - secondsOfAllowedClockSkew) >= claims.getExpirationTime().getValue()) {
              logger.info("Cached jwt token is expired!");
              throw new ExpiredTokenException("Token is expired");
            }
          }
 catch (          MalformedClaimException e) {
            logger.error("MalformedClaimException:",e);
          }
        }
        return claims;
      }
    }
    JwtConsumer consumer=new JwtConsumerBuilder().setSkipAllValidators().setDisableRequireSignature().setSkipSignatureVerification().build();
    JwtContext jwtContext=consumer.process(jwt);
    claims=jwtContext.getJwtClaims();
    JsonWebStructure structure=jwtContext.getJoseObjects().get(0);
    String kid=structure.getKeyIdHeaderValue();
    if (!ignoreExpiry) {
      try {
        if ((NumericDate.now().getValue() - secondsOfAllowedClockSkew) >= claims.getExpirationTime().getValue()) {
          logger.info("jwt token is expired!");
          throw new ExpiredTokenException("Token is expired");
        }
      }
 catch (      MalformedClaimException e) {
        logger.error("MalformedClaimException:",e);
        throw new InvalidJwtException("MalformedClaimException",new ErrorCodeValidator.Error(ErrorCodes.MALFORMED_CLAIM,"Invalid ExpirationTime Format"),e,jwtContext);
      }
    }
    X509Certificate certificate=certMap == null ? null : certMap.get(kid);
    if (certificate == null) {
      certificate=getCertFromOauth(kid);
      if (certMap == null)       certMap=new HashMap<>();
      certMap.put(kid,certificate);
    }
    X509VerificationKeyResolver x509VerificationKeyResolver=new X509VerificationKeyResolver(certificate);
    x509VerificationKeyResolver.setTryAllOnNoThumbHeader(true);
    consumer=new JwtConsumerBuilder().setRequireExpirationTime().setAllowedClockSkewInSeconds(315360000).setSkipDefaultAudienceValidation().setVerificationKeyResolver(x509VerificationKeyResolver).build();
    jwtContext=consumer.process(jwt);
    claims=jwtContext.getJwtClaims();
    if (Boolean.TRUE.equals(enableJwtCache)) {
      cache.put(jwt,claims);
    }
    return claims;
  }
  public static X509Certificate getCertFromOauth(  String kid){
    X509Certificate certificate=null;
    KeyRequest keyRequest=new KeyRequest(kid);
    try {
      String key=OauthHelper.getKey(keyRequest);
      CertificateFactory cf=CertificateFactory.getInstance("X.509");
      certificate=(X509Certificate)cf.generateCertificate(new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)));
    }
 catch (    Exception e) {
      logger.error("Exception: ",e);
      throw new RuntimeException(e);
    }
    return certificate;
  }
  /** 
 * Get a list of certificate fingerprints for server info endpoint so that certification process in light-portal can detect if your service still use the default public key certificates provided by the light-4j framework. The default public key certificates are for dev only and should be replaced on any other environment or set bootstrapFromKeyService: true if you are using light-oauth2 so that key can be dynamically loaded.
 * @return List of certificate fingerprints
 */
  public static List getFingerPrints(){
    return fingerPrints;
  }
  public JwtHelper(){
  }
}
