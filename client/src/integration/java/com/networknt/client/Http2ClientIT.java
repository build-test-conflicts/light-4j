package com.networknt.client;
import com.networknt.config.Config;
import com.networknt.httpstring.HttpStringConstants;
import com.networknt.utility.Constants;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.*;
import io.undertow.io.Receiver;
import io.undertow.io.Sender;
import io.undertow.protocols.ssl.UndertowXnioSsl;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.*;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.lang.JoseException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.*;
import org.xnio.ssl.XnioSsl;
import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
public class Http2ClientIT {
  static Logger logger=LoggerFactory.getLogger(Http2ClientIT.class);
  static Undertow server=null;
  static SSLContext sslContext;
  private static String message="Hello World!";
  public static String MESSAGE="/message";
  public static String POST="/post";
  public static String FORM="/form";
  public static String TOKEN="/oauth2/token";
  public static String API="/api";
  public static String KEY="/oauth2/key";
  private static String SERVER_KEY_STORE="server.keystore";
  private static String SERVER_TRUST_STORE="server.truststore";
  private static String CLIENT_KEY_STORE="client.keystore";
  private static String CLIENT_TRUST_STORE="client.truststore";
  private static char[] STORE_PASSWORD="password".toCharArray();
  private static XnioWorker worker;
  private static ThreadGroup threadGroup=new ThreadGroup("http2-client-test");
  private static URI ADDRESS;
static {
    try {
      ADDRESS=new URI("http://localhost:7777");
    }
 catch (    URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }
  public static void sendMessage(  final HttpServerExchange exchange){
    exchange.setStatusCode(StatusCodes.OK);
    exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH,message.length() + "");
    final Sender sender=exchange.getResponseSender();
    sender.send(message);
    sender.close();
  }
  @BeforeClass public static void beforeClass() throws IOException {
    final Xnio xnio=Xnio.getInstance();
    worker=xnio.createWorker(threadGroup,Http2Client.DEFAULT_OPTIONS);
    if (server == null) {
      System.out.println("starting server");
      Undertow.Builder builder=Undertow.builder();
      sslContext=createSSLContext(loadKeyStore(SERVER_KEY_STORE),loadKeyStore(SERVER_TRUST_STORE),false);
      builder.addHttpsListener(7778,"localhost",sslContext);
      builder.addHttpListener(7777,"localhost");
      builder.setServerOption(UndertowOptions.ENABLE_HTTP2,true);
      server=builder.setBufferSize(1024 * 16).setIoThreads(Runtime.getRuntime().availableProcessors() * 2).setSocketOption(Options.BACKLOG,10000).setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE,false).setServerOption(UndertowOptions.ALWAYS_SET_DATE,true).setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME,false).setSocketOption(Options.SSL_ENABLED_PROTOCOLS,Sequence.of("TLSv1.2")).setHandler(new PathHandler().addExactPath(MESSAGE,exchange -> sendMessage(exchange)).addExactPath(KEY,exchange -> sendMessage(exchange)).addExactPath(API,(exchange) -> {
        boolean hasScopeToken=exchange.getRequestHeaders().contains(HttpStringConstants.SCOPE_TOKEN);
        Assert.assertTrue(hasScopeToken);
        String scopeToken=exchange.getRequestHeaders().get(HttpStringConstants.SCOPE_TOKEN,0);
        boolean expired=isTokenExpired(scopeToken);
        Assert.assertFalse(expired);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,"application/json");
        exchange.getResponseSender().send(ByteBuffer.wrap(Config.getInstance().getMapper().writeValueAsBytes(Collections.singletonMap("message","OK!"))));
      }
).addExactPath(FORM,exchange -> exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback(){
        @Override public void handle(        HttpServerExchange exchange,        String message){
          exchange.getResponseSender().send(message);
        }
      }
)).addExactPath(TOKEN,exchange -> exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback(){
        @Override public void handle(        HttpServerExchange exchange,        String message){
          try {
            int sleepTime=randInt(1,3) * 1000;
            if (sleepTime >= 2000) {
              sleepTime=3000;
            }
 else {
              sleepTime=1000;
            }
            Thread.sleep(sleepTime);
            Map<String,Object> map=new HashMap<>();
            String token=getJwt(5);
            map.put("access_token",token);
            map.put("token_type","Bearer");
            map.put("expires_in",5);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE,"application/json");
            exchange.getResponseSender().send(ByteBuffer.wrap(Config.getInstance().getMapper().writeValueAsBytes(map)));
          }
 catch (          Exception e) {
            e.printStackTrace();
          }
        }
      }
)).addExactPath(POST,exchange -> exchange.getRequestReceiver().receiveFullString(new Receiver.FullStringCallback(){
        @Override public void handle(        HttpServerExchange exchange,        String message){
          exchange.getResponseSender().send(message);
        }
      }
))).setWorkerThreads(200).build();
      server.start();
    }
  }
  @AfterClass public static void afterClass(){
    worker.shutdown();
    if (server != null) {
      System.out.println("Stopping server.");
      try {
        server.stop();
        System.out.println("The server is stopped.");
        Thread.sleep(100);
      }
 catch (      InterruptedException ignored) {
      }
    }
  }
  public static Http2Client createClient(){
    return createClient(OptionMap.EMPTY);
  }
  public static Http2Client createClient(  final OptionMap options){
    return Http2Client.getInstance();
  }
  @Test public void testMultipleHttpGet() throws Exception {
    final Http2Client client=createClient();
    final List<AtomicReference<ClientResponse>> references=new CopyOnWriteArrayList<>();
    final CountDownLatch latch=new CountDownLatch(10);
    final ClientConnection connection=client.connect(ADDRESS,worker,Http2Client.BUFFER_POOL,OptionMap.EMPTY).get();
    try {
      connection.getIoThread().execute(new Runnable(){
        @Override public void run(){
          for (int i=0; i < 10; i++) {
            AtomicReference<ClientResponse> reference=new AtomicReference<>();
            references.add(i,reference);
            final ClientRequest request=new ClientRequest().setMethod(Methods.GET).setPath(MESSAGE);
            request.getRequestHeaders().put(Headers.HOST,"localhost");
            connection.sendRequest(request,client.createClientCallback(reference,latch));
          }
        }
      }
);
      latch.await(10,TimeUnit.SECONDS);
      Assert.assertEquals(10,references.size());
      for (      final AtomicReference<ClientResponse> reference : references) {
        Assert.assertEquals(message,reference.get().getAttachment(Http2Client.RESPONSE_BODY));
        Assert.assertEquals("HTTP/1.1",reference.get().getProtocol().toString());
      }
    }
  finally {
      IoUtils.safeClose(connection);
    }
  }
  @Test public void testMultipleHttpPost() throws Exception {
    fi