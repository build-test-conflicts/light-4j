package com.networknt.config;
import junit.framework.TestCase;
import org.junit.Assert;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
/** 
 * Created by steve on 23/09/16.
 */
public class ConfigClassPathTest extends TestCase {
  private Config config=null;
  private String homeDir=System.getProperty("user.home");
  @Override public void setUp() throws Exception {
    super.setUp();
    config=Config.getInstance();
    Map<String,Object> map=new HashMap<>();
    map.put("value","classpath");
    config.getMapper().writeValue(new File(homeDir + "/test.json"),map);
    addURL(new File(homeDir).toURI().toURL());
  }
  @Override public void tearDown() throws Exception {
    super.tearDown();
    File test=new File(homeDir + "/test.json");
    test.delete();
  }
  public void testGetConfigFromClassPath(){
    config.clear();
    Map<String,Object> configMap=config.getJsonMapConfig("test");
    Assert.assertEquals("classpath",configMap.get("value"));
  }
  @SuppressWarnings("unchecked") public void addURL(  URL url) throws Exception {
    URLClassLoader classLoader=(URLClassLoader)ClassLoader.getSystemClassLoader();
    Class clazz=URLClassLoader.class;
    Method method=clazz.getDeclaredMethod("addURL",URL.class);
    method.setAccessible(true);
    method.invoke(classLoader,url);
  }
  public ConfigClassPathTest(){
  }
}
