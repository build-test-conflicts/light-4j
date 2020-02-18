package com.networknt.server;
import com.networknt.client.Http2Client;
import com.networknt.common.SecretConstants;
import com.networknt.config.Config;
import com.networknt.handler.Handler;
import com.networknt.handler.HandlerProvider;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.handler.OrchestrationHandler;
import com.networknt.registry.Registry;
import com.networknt.registry.URL;
import com.networknt.registry.URLImpl;
import com.networknt.service.SingletonServiceFactory;
import com.networknt.switcher.SwitcherUtil;
import com.networknt.utility.Constants;
import com.networknt.utility.Util;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.xnio.IoUtils;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.SslClientAuthMode;
import javax.net.ssl.*;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import static java.nio.charset.StandardCharsets.UTF_8;
/** 
 * This is the entry point of the framework. It wrapped Undertow Core HTTP server and controls the lifecycle of the server. It also orchestrate different types of plugins and wire them in at the right location.
 * @author Steve Hu
 */
public class Server {
  static Logger logger=LoggerFactory.getLogger(Server.class);
  public static String CONFIG_NAME="server";
  public static String CONFIG_SECRET="secret";
  public static String[] STATUS_CONFIG_NAME={"status","app-status"};
  static String DEFAULT_ENV="test";
  static String LIGHT_ENV="light-env";
  static String LIGHT_CONFIG_SERVER_URI="light-config-server-uri";
  static String STATUS_HOST_IP="STATUS_HOST_IP";
  static String SID="sId";
  public static ServerConfig config=(ServerConfig)Config.getInstance().getJsonObjectConfig(CONFIG_NAME,ServerConfig.class);
  public static Map<String,Object> secret=Config.getInstance().getJsonMapConfig(CONFIG_SECRET);
  public static TrustManager[] TRUST_ALL_CERTS=new X509TrustManager[]{new DummyTrustManager()};
  static boolean shutdownRequested=false;
  static Undertow server=null;
  static URL serviceUrl;
  static Registry registry;
  static SSLContext sslContext;
  static GracefulShutdownHandler gracefulShutdownHandler;
  public static void main(  final String[] args){
    init();
  }
  public static void init(){
    logger.info("server starts");
    System.setProperty("org.jboss.logging.provider","slf4j");
    MDC.put(SID,config.getServiceId());
    try {
      loadConfig();
      mergeStatusConfig();
      start();
    }
 catch (    RuntimeException e) {
      logger.error("Server is not operational! Failed with exception",e);
      System.exit(1);
    }
  }
  public static public void start(){
    addDaemonShutdownHook();
    StartupHookProvider[] startupHookProviders=SingletonServiceFactory.getBeans(StartupHookProvider.class);
    if (startupHookProviders != null)     Arrays.stream(startupHookProviders).forEach(s -> s.onStartup());
    if (Handler.config == null || !Handler.config.isEnabled()) {
      HttpHandler handler=middlewareInit();
      gracefulShutdownHandler=new GracefulShutdownHandler(handler);
    }
 else {
      Handler.init();
      gracefulShutdownHandler=new GracefulShutdownHandler(new OrchestrationHandler());
    }
    if (config.dynamicPort) {
      if (config.minPort > config.maxPort) {
        String errMessage="No ports available to bind to - the minPort is larger than the maxPort in server.yml";
        System.out.println(errMessage);
        logger.error(errMessage);
        throw new RuntimeException(errMessage);
      }
      for (int i=config.minPort; i <= config.maxPort; i++) {
        boolean b=bind(gracefulShutdownHandler,i);
        if (b) {
          break;
        }
      }
    }
 else {
      bind(gracefulShutdownHandler,-1);
    }
  }
  public static HttpHandler middlewareInit(){
    HttpHandler handler=null;
    HandlerProvider handlerProvider=SingletonServiceFactory.getBean(HandlerProvider.class);
    if (handlerProvider != null) {
      handler=handlerProvider.getHandler();
    }
    if (handler == null) {
      logger.error("Unable to start the server - no route handler provider available in service.yml");
      throw new RuntimeException("Unable to start the server - no route handler provider available in service.yml");
    }
    MiddlewareHandler[] middlewareHandlers=SingletonServiceFactory.getBeans(MiddlewareHandler.class);
    if (middlewareHandlers != null) {
      for (int i=middlewareHandlers.length - 1; i >= 0; i--) {
        logger.info("Plugin: " + middlewareHandlers[i].getClass().getName());
        if (middlewareHandlers[i].isEnabled()) {
          handler=middlewareHandlers[i].setNext(handler);
          middlewareHandlers[i].register();
        }
      }
    }
    return handler;
  }
  /** 
 * Method used to initialize server options. If the user has configured a valid server option, load it into the server configuration, otherwise use the default value
 */
  public static void serverOptionInit(){
    Map<String,Object> mapConfig=Config.getInstance().getJsonMapConfigNoCache(CONFIG_NAME);
    ServerOption.serverOptionInit(mapConfig,config);
  }
  public static public boolean bind(  HttpHandler handler,  int port){
    try {
      Undertow.Builder builder=Undertow.builder();
      if (config.enableHttps) {
        port=port < 0 ? config.getHttpsPort() : port;
        sslContext=createSSLContext();
        builder.addHttpsListener(port,config.getIp(),sslContext);
      }
 else       if (config.enableHttp) {
        port=port < 0 ? config.getHttpPort() : port;
        builder.addHttpListener(port,config.getIp());
      }
 else {
        throw new RuntimeException("Unable to start the server as both http and https are disabled in server.yml");
      }
      if (config.enableHttp2) {
        builder.setServerOption(UndertowOptions.ENABLE_HTTP2,true);
      }
      if (config.isEnableTwoWayTls()) {
        builder.setSocketOption(Options.SSL_CLIENT_AUTH_MODE,SslClientAuthMode.REQUIRED);
      }
      serverOptionInit();
      server=builder.setBufferSize(config.getBufferSize()).setIoThreads(config.getIoThreads()).setSocketOption(Options.BACKLOG,config.getBacklog()).setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE,false).setServerOption(UndertowOptions.ALWAYS_SET_DATE,config.isAlwaysSetDate()).setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME,false).setHandler(Handlers.header(handler,Headers.SERVER_STRING,config.getServerString())).setWorkerThreads(config.getWorkerThreads()).build();
      server.start();
      System.out.println("HOST IP " + System.getenv(STATUS_HOST_IP));
    }
 catch (    Exception e) {
      if (!config.dynamicPort || (config.dynamicPort && config.maxPort == port + 1)) {
        String triedPortsMessage=config.dynamicPort ? config.minPort + " to: " + (config.maxPort - 1) : port + "";
        String errMessage="No ports available to bind to. Tried: " + triedPortsMessage;
        System.out.println(errMessage);
        logger.error(errMessage);
        throw new RuntimeException(errMessage,e);
      }
      System.out.println("Failed to bind to port " + port + ". Trying "+ ++port);
      if (logger.isInfoEnabled())       logger.info("Failed to bind to port " + port + ". Trying "+ ++port);
      return false;
    }
    if (config.enableRegistry) {
      try {
        registry=SingletonServiceFactory.getBean(Registry.class);
        if (registry == null)         throw new RuntimeException("Could not find registry instance in service map");
        String ipAddress=System.getenv(STATUS_HOST_IP);
        logger.info("Registry IP from STATUS_HOST_IP is " + ipAddress);
        if (ipAddress == null) {
          InetAddress inetAddress=Util.getInetAddress();
          ipAddress=inetAddress.getHostAddress();
          logger.info("Could not find IP from STATUS_HOST_IP, use the InetAddress " + ipAddress);
        }
        Map parameters=new HashMap<>();
        if (config.getEnvironment() != null)         parameters.put("environment",config.getEnvironment());
        serviceUrl=new URLImpl("light",ipAddress,port,config.getServiceId(),parameters);
        registry.register(serviceUrl);
        if (logger.isInfoEnabled())         logger.info("register service: " + serviceUrl.toFullStr());
        SwitcherUtil.setSwitcherValue(Constants.REGISTRY_HEARTBEAT_SWITCHER,true);
        if (logger.isInfoEnabled())         logger.info("Registry heart beat switcher is on");
      }
 catch (      Exception e) {
        System.out.println("Failed to register service, the server stopped.");
        if (logger.isInfoEnabled())         logger.info("Failed to register service, the server stopped.");
        throw new RuntimeException(e.getMessage());
      }
    }
    if (config.enableHttp) {
      System.out.println("Http Server started on ip:" + config.getIp() + " Port:"+ port);
      if (logger.isInfoEnabled())       logger.info("Http Server started on ip:" + config.getIp() + " Port:"+ port);
    }
 else {
      System.out.println("Http port disabled.");
      if (logger.isInfoEnabled())       logger.info("Http port disabled.");
    }
    if (config.enableHttps) {
      System.out.println("Https Server started on ip:" + config.getIp() + " Port:"+ port);
      if (logger.isInfoEnabled())       logger.info("Https Server started on ip:" + config.getIp() + " Port:"+ port);
    }
 else {
      System.out.println("Https port disabled.");
      if (logger.isInfoEnabled())       logger.info("Https port disabled.");
    }
    return true;
  }
  public static public void stop(){
    if (server != null)     server.stop();
  }
  public static public void shutdown(){
    if (config.enableRegistry && registry != null) {
      registry.unregister(serviceUrl);
      System.out.println("unregister serviceUrl " + serviceUrl);
      if (logger.isInfoEnabled())       logger.info("unregister serviceUrl " + serviceUrl);
    }
    if (gracefulShutdownHandler != null) {
      logger.info("Starting graceful shutdown.");
      gracefulShutdownHandler.shutdown();
      try {
        gracefulShutdownHandler.awaitShutdown(60 * 1000);
      }
 catch (      InterruptedException e) {
        logger.error("Error occurred while waiting for pending requests to complete.",e);
      }
      logger.info("Graceful shutdown complete.");
    }
    ShutdownHookProvider[] shutdownHookProviders=SingletonServiceFactory.getBeans(ShutdownHookProvider.class);
    if (shutdownHookProviders != null)     Arrays.stream(shutdownHookProviders).forEach(s -> s.onShutdown());
    stop();
    logger.info("Cleaning up before server shutdown");
  }
  public static void addDaemonShutdownHook(){
    Runtime.getRuntime().addShutdownHook(new Thread(){
      @Override public void run(){
        Server.shutdown();
      }
    }
);
  }
  public static KeyStore loadKeyStore(){
    String name=config.getKeystoreName();
    try (InputStream stream=Config.getInstance().getInputStreamFromFile(name)){
      KeyStore loadedKeystore=KeyStore.getInstance("JKS");
      loadedKeystore.load(stream,((String)secret.get(SecretConstants.SERVER_KEYSTORE_PASS)).toCharArray());
      return loadedKeystore;
    }
 catch (    Exception e) {
      logger.error("Unable to load keystore " + name,e);
      throw new RuntimeException("Unable to load keystore " + name,e);
    }
  }
  public static KeyStore loadTrustStore(){
    String name=config.getTruststoreName();
    try (InputStream stream=Config.getInstance().getInputStreamFromFile(name)){
      KeyStore loadedKeystore=KeyStore.getInstance("JKS");
      loadedKeystore.load(stream,((String)secret.get(SecretConstants.SERVER_TRUSTSTORE_PASS)).toCharArray());
      return loadedKeystore;
    }
 catch (    Exception e) {
      logger.error("Unable to load truststore " + name,e);
      throw new RuntimeException("Unable to load truststore " + name,e);
    }
  }
  public static TrustManager[] buildTrustManagers(  final KeyStore trustStore){
    TrustManager[] trustManagers=null;
    if (trustStore != null) {
      try {
        TrustManagerFactory trustManagerFactory=TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        trustManagers=trustManagerFactory.getTrustManagers();
      }
 catch (      NoSuchAlgorithmException|KeyStoreException e) {
        logger.error("Unable to initialise TrustManager[]",e);
        throw new RuntimeException("Unable to initialise TrustManager[]",e);
      }
    }
 else {
      logger.warn("Unable to find server truststore while Mutual TLS is enabled. Falling back to trust all certs.");
      trustManagers=TRUST_ALL_CERTS;
    }
    return trustManagers;
  }
  public static KeyManager[] buildKeyManagers(  final KeyStore keyStore,  char[] keyPass){
    KeyManager[] keyManagers;
    try {
      KeyManagerFactory keyManagerFactory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keyStore,keyPass);
      keyManagers=keyManagerFactory.getKeyManagers();
    }
 catch (    NoSuchAlgorithmException|UnrecoverableKeyException|KeyStoreException e) {
      logger.error("Unable to initialise KeyManager[]",e);
      throw new RuntimeException("Unable to initialise KeyManager[]",e);
    }
    return keyManagers;
  }
  public static SSLContext createSSLContext() throws RuntimeException {
    try {
      KeyManager[] keyManagers=buildKeyManagers(loadKeyStore(),((String)secret.get(SecretConstants.SERVER_KEY_PASS)).toCharArray());
      TrustManager[] trustManagers;
      if (config.isEnableTwoWayTls()) {
        trustManagers=buildTrustManagers(loadTrustStore());
      }
 else {
        trustManagers=buildTrustManagers(null);
      }
      SSLContext sslContext;
      sslContext=SSLContext.getInstance("TLSv1");
      sslContext.init(keyManagers,trustManagers,null);
      return sslContext;
    }
 catch (    Exception e) {
      logger.error("Unable to create SSLContext",e);
      throw new RuntimeException("Unable to create SSLContext",e);
    }
  }
  /** 
 * Load config files from light-config-server instance. This is normally only used when you run light-4j server as standalone java process. If the server is dockerized and orchestrated by Kubernetes, the config files and secret will be mapped to Kubernetes ConfigMap and Secret and passed into the container. <p> Of course, you can still use it with standalone docker container but it is not recommended.
 */
  public static void loadConfig(){
    String env=System.getProperty(LIGHT_ENV);
    if (env == null) {
      logger.warn("Warning! No light-env has been passed in from command line. Default to dev");
      env=DEFAULT_ENV;
    }
    String configUri=System.getProperty(LIGHT_CONFIG_SERVER_URI);
    if (configUri != null) {
      String targetMergeDirectory=System.getProperty(Config.LIGHT_4J_CONFIG_DIR);
      if (targetMergeDirectory == null) {
        logger.warn("Warning! No light-4j-config-dir has been passed in from command line.");
        return;
      }
      String version=Util.getJarVersion();
      String service=config.getServiceId();
      String tempDir=System.getProperty("java.io.tmpdir");
      String zipFile=tempDir + "/config.zip";
      String path="/v1/config/" + version + "/"+ env+ "/"+ service;
      Http2Client client=Http2Client.getInstance();
      ClientConnection connection=null;
      try {
        connection=client.connect(new URI(configUri),Http2Client.WORKER,Http2Client.SSL,Http2Client.BUFFER_POOL,OptionMap.create(UndertowOptions.ENABLE_HTTP2,true)).get();
      }
 catch (      Exception e) {
        logger.error("Exeption:",e);
      }
      final CountDownLatch latch=new CountDownLatch(1);
      final AtomicReference<ClientResponse> reference=new AtomicReference<>();
      try {
        ClientRequest request=new ClientRequest().setMethod(Methods.GET).setPath(path);
        request.getRequestHeaders().put(Headers.HOST,"localhost");
        connection.sendRequest(request,client.createClientCallback(reference,latch));
        latch.await();
        int statusCode=reference.get().getResponseCode();
        if (statusCode >= 300) {
          logger.error("Failed to load config from config server" + statusCode + ":"+ reference.get().getAttachment(Http2Client.RESPONSE_BODY));
          throw new Exception("Failed to load config from config server: " + statusCode);
        }
 else {
          FileOutputStream fos=new FileOutputStream(zipFile);
          fos.write(reference.get().getAttachment(Http2Client.RESPONSE_BODY).getBytes(UTF_8));
          fos.close();
          unzipFile(zipFile,targetMergeDirectory);
        }
      }
 catch (      Exception e) {
        logger.error("Exception:",e);
      }
 finally {
        IoUtils.safeClose(connection);
      }
    }
 else {
      logger.info("light-config-server-uri is missing in the command line. Use local config files");
    }
  }
  public static void mergeConfigFiles(  String source,  String target){
  }
  public static void unzipFile(  String path,  String target){
    try (ZipFile file=new ZipFile(path)){
      FileSystem fileSystem=FileSystems.getDefault();
      Enumeration<? extends ZipEntry> entries=file.entries();
      Files.createDirectory(fileSystem.getPath(target));
      while (entries.hasMoreElements()) {
        ZipEntry entry=entries.nextElement();
        if (entry.isDirectory()) {
          System.out.println("Creating Directory:" + target + entry.getName());
          Files.createDirectories(fileSystem.getPath(target + entry.getName()));
        }
 else {
          InputStream is=file.getInputStream(entry);
          BufferedInputStream bis=new BufferedInputStream(is);
          String uncompressedFileName=target + entry.getName();
          Path uncompressedFilePath=fileSystem.getPath(uncompressedFileName);
          Files.createFile(uncompressedFilePath);
          FileOutputStream fileOutput=new FileOutputStream(uncompressedFileName);
          while (bis.available() > 0) {
            fileOutput.write(bis.read());
          }
          fileOutput.close();
          System.out.println("Written :" + entry.getName());
        }
      }
    }
 catch (    IOException e) {
      logger.error("IOException",e);
    }
  }
  public static void mergeStatusConfig(){
    Map<String,Object> appStatusConfig=Config.getInstance().getJsonMapConfigNoCache(STATUS_CONFIG_NAME[1]);
    if (appStatusConfig == null) {
      return;
    }
    Map<String,Object> statusConfig=Config.getInstance().getJsonMapConfig(STATUS_CONFIG_NAME[0]);
    Set<String> duplicatedStatusSet=new HashSet<>(statusConfig.keySet());
    duplicatedStatusSet.retainAll(appStatusConfig.keySet());
    if (!duplicatedStatusSet.isEmpty()) {
      logger.error("The status code(s): " + duplicatedStatusSet.toString() + " is already in use by light-4j and cannot be overwritten,"+ " please change to another status code in app-status.yml if necessary.");
      throw new RuntimeException("The status code(s): " + duplicatedStatusSet.toString() + " in status.yml and app-status.yml are duplicated.");
    }
    statusConfig.putAll(appStatusConfig);
  }
  public Server(){
  }
}
