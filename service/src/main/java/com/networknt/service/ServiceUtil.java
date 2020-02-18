package com.networknt.service;
import java.beans.Introspector;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
/** 
 * Utility functions for instantiating objects that follow the configuration structure of the service.yml.
 * @author Nicholas Azar
 */
public class ServiceUtil {
  /** 
 * Instantiates and returns an object out of a given configuration. If the configuration is simply a class name, assume default empty constructor. If the configuration is a map, assume keyed by class name, with values being one of 2 options: - map: keys are field names, values are field values. - list: items are each maps of type to value, constructor is called with given set.
 */
  public static Object construct(  Object something) throws Exception {
    if (something instanceof String) {
      return Class.forName((String)something).newInstance();
    }
 else     if (something instanceof Map) {
      for (      Map.Entry<String,Object> entry : ((Map<String,Object>)something).entrySet()) {
        if (entry.getValue() instanceof Map) {
          return constructByNamedParams(Class.forName(entry.getKey()),(Map)entry.getValue());
        }
 else         if (entry.getValue() instanceof List) {
          return constructByParameterizedConstructor(Class.forName(entry.getKey()),(List)entry.getValue());
        }
      }
    }
    return null;
  }
  /** 
 * Build an object out of a given class and a map for field names to values.
 * @param clazz The class to be created.
 * @param params A map of the parameters.
 * @return An instantiated object.
 * @throws Exception
 */
  public static Object constructByNamedParams(  Class clazz,  Map params) throws Exception {
    Object obj=clazz.newInstance();
    Method[] allMethods=clazz.getMethods();
    for (    Method method : allMethods) {
      if (method.getName().startsWith("set")) {
        Object[] o=new Object[1];
        String propertyName=Introspector.decapitalize(method.getName().substring(3));
        if (params.containsKey(propertyName)) {
          o[0]=params.get(propertyName);
          method.invoke(obj,o);
        }
      }
    }
    return obj;
  }
  /** 
 * Build an object out of a given class and a list of single element maps of object type to value. A constructor is searched for that matches the given set. If not found, the default is attempted.
 * @param clazz The class to be created.
 * @param parameters A list of single element maps of object type to value.
 * @return An instantiated parameters.
 * @throws Exception
 */
  public static Object constructByParameterizedConstructor(  Class clazz,  List parameters) throws Exception {
    Object instance=null;
    Constructor[] allConstructors=clazz.getDeclaredConstructors();
    boolean hasDefaultConstructor=false;
    for (    Constructor ctor : allConstructors) {
      Class<?>[] pType=ctor.getParameterTypes();
      if (pType.length > 0) {
        if (pType.length == parameters.size()) {
          boolean matched=true;
          Object[] params=new Object[pType.length];
          for (int j=0; j < pType.length; j++) {
            Map<String,Object> parameter=(Map)parameters.get(j);
            Iterator it=parameter.entrySet().iterator();
            if (it.hasNext()) {
              Map.Entry<String,Object> pair=(Map.Entry)it.next();
              String key=pair.getKey();
              Object value=pair.getValue();
              if (pType[j].getName().equals(key)) {
                params[j]=value;
              }
 else {
                matched=false;
                break;
              }
            }
          }
          if (matched) {
            instance=ctor.newInstance(params);
            break;
          }
        }
      }
 else {
        hasDefaultConstructor=true;
      }
    }
    if (instance != null) {
      return instance;
    }
 else {
      if (hasDefaultConstructor) {
        return clazz.getConstructor().newInstance();
      }
 else {
        throw new Exception("No instance can be created for class " + clazz);
      }
    }
  }
  public ServiceUtil(){
  }
}
