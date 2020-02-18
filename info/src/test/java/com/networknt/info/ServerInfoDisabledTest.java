package com.networknt.info;
import com.networknt.client.Http2Client;
import com.networknt.config.Config;
import com.networknt.exception.ClientException;
import com.networknt.status.Status;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
/** 
 * Created by steve on 01/10/16.
 */
public class ServerInfoDisabledTest {
  static Logger logger=LoggerFactory.getLogger(ServerInfoGetHandlerTest.class);
  static Undertow server=null;
  static String homeDir=System.getProperty("user.home");
  @BeforeClass public static void setUp() throws Exception {
    Config.getInstance().clear();
    Map<String,Object> map=new HashMap<>();
    map.put("enableServerInfo",false);
    Config.getInstance().getYaml().dump(map,new PrintWriter(new File(homeDir + "/info.yml"),Charset.defaultCharset().name()));
    addURL(new File(homeDir).toURI().toURL());
    if (server == null) {
      logger.info("starting server");
      HttpHandler handler=getTestHandler();
      server=Undertow.builder().addHttpListener(8080,"localhost").setHandler(handler).build();
      server.start();
    }
  }
  @AfterClass public static void tearDown() throws Exception {
    if (server != null) {
      try {
        Thread.sleep(100);
      }
 catch (      InterruptedException ignored) {
      }
      server.stop();
      logger.info("The server is stopped.");
    }
    File configFile=new File(homeDir + "/info.yml");
    configFile.delete();
    Config.getInstance().clear();
  }
  public static void addURL(  URL url) throws Exception {
    URLClassLoader classLoader=(URLClassLoader)ClassLoader.getSystemClassLoader();
    Class clazz=URLClassLoader.class;
    Method method=clazz.getDeclaredMethod("addURL",URL.class);
    method.setAccessible(true);
    method.invoke(classLoader,url);
  }
  public static RoutingHandler getTestHandler(){
    return Handlers.routing().add(Methods.GET,"/v1/server/info",new ServerInfoGetHandler());
  }
  @Test public void testServerInfo() throws Exception {
    final Http2Client client=Http2Client.getInstance();
    final CountDownLatch latch=new CountDownLatch(1);
    final ClientConnection connection;
    try {
      connection=client.connect(new URI("http://localhost:8080"),Http2Client.WORKER,Http2Client.SSL,Http2Client.BUFFER_POOL,OptionMap.EMPTY).get();
    }
 catch (    Exception e) {
      throw new ClientException(e);
    }
    final AtomicReference<ClientResponse> reference=new AtomicReference<>();
    try {
      ClientRequest request=new ClientRequest().setPath("/v1/server/info").setMethod(Methods.GET);
      request.getRequestHeaders().put(Headers.HOST,"localhost");
      connection.sendRequest(request,client.createClientCallback(reference,latch));
      latch.await();
    }
 catch (    Exception e) {
      logger.error("Exception: ",e);
      throw new ClientException(e);
    }
 finally {
      IoUtils.safeClose(connection);
    }
    int statusCode=reference.get().getResponseCode();
    String body=reference.get().getAttachment(Http2Client.RESPONSE_BODY);
    Assert.assertEquals(404,statusCode);
    if (statusCode == 404) {
      Status status=Config.getInstance().getMapper().readValue(body,Status.class);
      Assert.assertNotNull(status);
      Assert.assertEquals("ERR10013",status.getCode());
    }
  }
  public ServerInfoDisabledTest(){
  }
}
