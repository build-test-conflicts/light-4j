package com.networknt.common;
import static com.networknt.decrypt.Decryptor.CRYPT_PREFIX;
import static java.lang.System.exit;
import java.io.UnsupportedEncodingException;
import java.security.AlgorithmParameters;
import java.security.InvalidKeyException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.KeySpec;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import com.networknt.utility.Constants;
import sun.misc.BASE64Encoder;
public class AESEncryptor {
  public static void main(  String[] args){
    if (args.length == 0) {
      System.out.println("Please provide plain text to encrypt!");
      exit(0);
    }
    AESEncryptor encryptor=new AESEncryptor();
    System.out.println(encryptor.encrypt(args[0]));
  }
  private static int ITERATIONS=65536;
  private static int KEY_SIZE=128;
  private static byte[] SALT={(byte)0x0,(byte)0x0,(byte)0x0,(byte)0x0,(byte)0x0,(byte)0x0,(byte)0x0,(byte)0x0};
  private static String STRING_ENCODING="UTF-8";
  private SecretKeySpec secret;
  private Cipher cipher;
  private BASE64Encoder base64Encoder;
  public AESEncryptor(){
    try {
      SecretKeyFactory factory=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
      KeySpec spec;
      spec=new PBEKeySpec(Constants.FRAMEWORK_NAME.toCharArray(),SALT,ITERATIONS,KEY_SIZE);
      SecretKey tmp=factory.generateSecret(spec);
      secret=new SecretKeySpec(tmp.getEncoded(),"AES");
      cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
      base64Encoder=new BASE64Encoder();
    }
 catch (    Exception e) {
      throw new RuntimeException("Unable to initialize",e);
    }
  }
  /** 
 * Encrypt given input string
 * @param input
 * @return
 * @throws RuntimeException
 */
  public String encrypt(  String input){
    try {
      byte[] inputBytes=input.getBytes(STRING_ENCODING);
      cipher.init(Cipher.ENCRYPT_MODE,secret);
      AlgorithmParameters params=cipher.getParameters();
      byte[] iv=params.getParameterSpec(IvParameterSpec.class).getIV();
      byte[] ciphertext=cipher.doFinal(inputBytes);
      byte[] out=new byte[iv.length + ciphertext.length];
      System.arraycopy(iv,0,out,0,iv.length);
      System.arraycopy(ciphertext,0,out,iv.length,ciphertext.length);
      return CRYPT_PREFIX + ":" + base64Encoder.encode(out);
    }
 catch (    IllegalBlockSizeException e) {
      throw new RuntimeException("Unable to encrypt",e);
    }
catch (    BadPaddingException e) {
      throw new RuntimeException("Unable to encrypt",e);
    }
catch (    InvalidKeyException e) {
      throw new RuntimeException("Unable to encrypt",e);
    }
catch (    InvalidParameterSpecException e) {
      throw new RuntimeException("Unable to encrypt",e);
    }
catch (    UnsupportedEncodingException e) {
      throw new RuntimeException("Unable to encrypt",e);
    }
  }
}
