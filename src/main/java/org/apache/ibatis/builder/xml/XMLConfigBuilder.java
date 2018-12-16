/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  private boolean parsed;
  private final XPathParser parser;
  private String environment;
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    // XPathParser是用来解析xml文件的，XMLConfigBuilder、XMLMapperBuilder它们都是继承于BaseBuilder，xml的加载于解析委托给XPathParser
    // XMLMapperEntityResolver是用来指定xml对应的XSD或DTD如何获取
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    // 创建Configuration实例; 并将Configuration实例及Configuration的typeAliasRegistry、typeHandlerRegistry属性置为自身属性(这三个属性都是继承自BaseBuilder)
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  public Configuration parse() {
    if (parsed) { // 配置文件只能解析一次
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // parser.evalNode("/configuration") 从配置文件中获取configuration节点
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      // root.evalNode("xxxx") 调用XML DOM的evaluate()方法，根据给定的节点表达式来计算指定的 XPath 表达式，
      // 并且返回一个XPathResult对象，返回类型在Node.evalNode()方法中均被指定为NODE
      propertiesElement(root.evalNode("properties")); // 加载properties节点
      Properties settings = settingsAsProperties(root.evalNode("settings")); // 加载settings节点
      loadCustomVfs(settings);
      typeAliasesElement(root.evalNode("typeAliases")); // 加载解析别名 typeAliases
      pluginElement(root.evalNode("plugins")); // 加载plugins节点
      objectFactoryElement(root.evalNode("objectFactory")); // 加载objectFactory节点
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory")); // 加载objectWrapperFactory节点
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      environmentsElement(root.evalNode("environments")); // 加载environments节点
      databaseIdProviderElement(root.evalNode("databaseIdProvider")); // 加载databaseIdProvider节点
      typeHandlerElement(root.evalNode("typeHandlers")); // 加载typeHandlers节点
      mapperElement(root.evalNode("mappers")); // 加载mappers节点
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    /*
      settings节点配置样例:
      <settings>
        <setting name="cacheEnabled" value="true"/>
      </settings>
     */
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    /*
      指定vfs的实现
      VFS主要用来加载容器内的各种资源，比如jar或者class文件。mybatis提供了2个实现 JBoss6VFS 和 DefaultVFS，并提供了用户扩展点，用于自定义VFS实现，
      加载顺序是自定义VFS实现 > 默认VFS实现 取第一个加载成功的，默认情况下会先加载JBoss6VFS，如果classpath下找不到jboss的vfs实现才会加载默认VFS实现
     */
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
            /*
                <typeAliases>
                  <package name="domain.blog"/>
                </typeAliases>
                在没有注解的情况下，domain.blog包下的所有类都将使用类名的首字母小写作为别名
                但是在底层里发现typeAliasRegistry.registerAlias(alias, clazz)方法会将 alias.toLowerCase(Locale.ENGLISH) 作为key
             */
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
            /*
                <typeAliases>
                  <typeAlias alias="Blog" type="domain.blog.Blog"/>
                </typeAliases>
             */
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
                // 默认使用clazz.getSimpleName()作为别名，若有Alias注解则使用注解值作为别名;然后再调用typeAliasRegistry.registerAlias(alias, clazz);
              typeAliasRegistry.registerAlias(clazz);
            } else {
              // 将 alias.toLowerCase(Locale.ENGLISH) 作为key
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  private void pluginElement(XNode parent) throws Exception {
      /*
        plugins的配置样例：
        <plugins>
            <plugin interceptor="org.apache.ibatis.builder.ExamplePlugin">
                <property name="" value=""/>
            </plugin>
        </plugins>
        插件在具体实现的时候，采用的是拦截器模式，要注册为mybatis插件，必须实现org.apache.ibatis.plugin.Interceptor接口，每个插件可以有自己的属性。
        interceptor属性值既可以完整的类名，也可以是别名，只要别名在typealias中存在即可，如果启动时无法解析，会抛出ClassNotFound异常。
        实例化插件后，将设置插件的属性赋值给插件实现类的属性字段
        mybatis提供了两个内置的插件例子：org.apache.ibatis.plugin.PluginTest、org.apache.ibatis.builder.ExamplePlugin
       */
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        Properties properties = child.getChildrenAsProperties();
        // resolveClass(interceptor) 其实就是先将interceptor当做别名到typeAliasRegistry中查找有没有对应的类，有则直接返回
        // 若未查询到则使用Class.forName()创建
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        interceptorInstance.setProperties(properties);
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    /*
      objectFactory的配置示例：
      <objectFactory type="org.apache.ibatis.builder.ExampleObjectFactory">
          <property name="" value=""></property>
      </objectFactory>
      什么是对象工厂？ MyBatis 每次创建结果对象的新实例时，它都会使用一个对象工厂（ObjectFactory）实例来完成。
      默认的对象工厂DefaultObjectFactory做的仅仅是实例化目标类，要么通过默认构造方法，要么在参数映射存在的时候通过参数构造方法来实例化
     */
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties properties = context.getChildrenAsProperties();
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    /*
      objectWrapperFactory配置示例:
      <objectWrapperFactory type="org.apache.ibatis.reflection.wrapper.DefaultObjectWrapperFactory" />
      对象包装器工厂主要用来包装返回result对象，比如说可以用来设置某些敏感字段脱敏或者加密等。
      默认对象包装器工厂是DefaultObjectWrapperFactory，也就是不使用包装器工厂
     */
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
       String type = context.getStringAttribute("type");
       ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
       configuration.setReflectorFactory(factory);
    }
  }

  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      // 加载所有property子节点，并解析成Properties
      /*
        properties配置示例:
          <properties resource="db.properties">
            <property name="username" value="root"/>
            <property name="password" value="123456"/>
          </properties>
       */
      Properties defaults = context.getChildrenAsProperties();
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      // resource和url不能同时设置
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      // 子节点配置会被覆盖
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      /*
        properties的配置规则：
        1. 通过设置properties节点的url或resource属性从外部加载一个properties文件
        2. 通过设置properties节点的property子节点进行配置，若子节点的key在外部properties文件中已存在，已外部properties文件为准
        3. 通过编程方式定义的属性优先级最高
        优先级： 编程方式 > 外部properties文件 > property子节点
       */
      // 设置到XPathParser
      parser.setVariables(defaults);
      // 设置到Configuration
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) throws Exception {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>)resolveClass(props.getProperty("defaultEnumTypeHandler"));
    configuration.setDefaultEnumTypeHandler(typeHandler);
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    @SuppressWarnings("unchecked")
    Class<? extends Log> logImpl = (Class<? extends Log>)resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  private void environmentsElement(XNode context) throws Exception {
    /*
      配置样例:
      <environments default="development">
          <environment id="development">
              <transactionManager type="JDBC"/>
              <dataSource type="POOLED">
                  <property name="driver" value="com.mysql.jdbc.Driver"/>
                  <property name="url" value="jdbc:mysql://localhost:3306/test?useUnicode=true"/>
                  <property name="username" value="root"/>
                  <property name="password" value="1234"/>
              </dataSource>
          </environment>
      </environments>
     */
    if (context != null) {
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");
        // 查找指定的environment(environment.equals(id))
        if (isSpecifiedEnvironment(id)) {
          // 事务配置并创建事务工厂
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 数据源配置加载并实例化数据源, 数据源是必备的
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          // environmentBuilder的build()返回 Environment 实现意图？
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
    /*
      MyBatis 可以根据不同的数据库厂商执行不同的语句，这种多厂商的支持是基于映射语句中的 databaseId 属性。
      MyBatis 会加载不带 databaseId 属性和带有匹配当前数据库 databaseId 属性的所有语句。
      如果同时找到带有 databaseId 和不带 databaseId 的相同语句，则后者会被舍弃
      配置示例:
        <databaseIdProvider type="DB_VENDOR" />
     */
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      /*
        这里的 DB_VENDOR 会通过 DatabaseMetaData#getDatabaseProductName() 返回的字符串进行设置。
        由于通常情况下这个字符串都非常长而且相同产品的不同版本会返回不同的值，所以最好通过设置属性别名来使其变短，如下：
        <databaseIdProvider type="DB_VENDOR">
          <property name="SQL Server" value="sqlserver"/>
          <property name="MySQL" value="mysql"/>
          <property name="Oracle" value="oracle" />
        </databaseIdProvider>
        在有 properties 时，DB_VENDOR databaseIdProvider 的将被设置为第一个能匹配数据库产品名称的属性键对应的值，
        如果没有匹配的属性将会设置为 “null”。
　　    因为每个数据库在实现的时候，getDatabaseProductName() 返回的通常并不是直接的Oracle或者MySQL，而是“Oracle (DataDirect)”，
        所以如果希望使用多数据库特性，一般需要实现 org.apache.ibatis.mapping.DatabaseIdProvider接口
        并在 mybatis-config.xml 中注册来构建自己的 DatabaseIdProvider
       */
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
          type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    /*
      配置样例：
      <transactionManager type="JDBC">
          <property name="" value=""></property>
      </transactionManager>
     */
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) throws Exception {
    /*
      无论是 MyBatis 在预处理语句（PreparedStatement）中设置一个参数时，还是从结果集中取出一个值时，
      都会用类型处理器将获取的值以合适的方式转换成 Java 类型。
　　   mybatis提供了两种方式注册类型处理器，package自动检索方式和显示定义方式。
      使用自动检索（autodiscovery）功能的时候，只能通过注解方式来指定 JDBC 的类型。

      通过类型处理器的泛型，MyBatis 可以得知该类型处理器处理的 Java 类型，不过这种行为可以通过两种方法改变：
      在类型处理器的配置元素（typeHandler element）上增加一个 javaType 属性（比如：javaType=”String”）；
      在类型处理器的类上（TypeHandler class）增加一个 @MappedTypes 注解来指定与其关联的 Java 类型列表。
      如果在 javaType 属性中也同时指定，则注解方式将被忽略。

      可以通过两种方式来指定被关联的 JDBC 类型：
      在类型处理器的配置元素上增加一个 jdbcType 属性（比如：jdbcType=”VARCHAR”）；
      在类型处理器的类上（TypeHandler class）增加一个 @MappedJdbcTypes 注解来指定与其关联的 JDBC 类型列表。
      如果在两个位置同时指定，则注解方式将被忽略。

      当决定在ResultMap中使用某一TypeHandler时，此时java类型是已知的（从结果类型中获得），但是JDBC类型是未知的。
      因此Mybatis使用javaType=[TheJavaType], jdbcType=null的组合来选择一个TypeHandler。
      这意味着使用@MappedJdbcTypes注解可以限制TypeHandler的范围，同时除非显示的设置，否则TypeHandler在ResultMap中将是无效的。
      如果希望在ResultMap中使用TypeHandler，那么设置@MappedJdbcTypes注解的includeNullJdbcType=true即可。
      然而从Mybatis 3.4.0开始，如果只有一个注册的TypeHandler来处理Java类型，那么它将是ResultMap使用Java类型时的默认值（即使没有includeNullJdbcType=true）?
     */
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          /*
            使用包配置:
            <typeHandlers>
              <package name="org.mybatis.example"/>
            </typeHandlers>
            为了简化使用，mybatis在初始化TypeHandlerRegistry期间，自动注册了大部分的常用的类型处理器比如字符串、数字、日期等。
            对于非标准的类型，用户可以自定义类型处理器来处理。
              要实现一个自定义类型处理器，只要实现 org.apache.ibatis.type.TypeHandler 接口，
              或继承一个实用类 org.apache.ibatis.type.BaseTypeHandler， 并将它映射到一个 JDBC 类型即可
           */
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          /*
            显示定义方式：
            <typeHandlers>
              <typeHandler handler="org.mybatis.example.ExampleTypeHandler"/>
            </typeHandlers>
           */
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 如果要同时使用package自动扫描和通过mapper明确指定要加载的mapper，一定要确保package自动扫描的范围不包含明确指定的mapper，
        // 否则在通过package扫描的interface的时候，尝试加载对应xml文件的loadXmlResource()的逻辑中出现判重出错，
        // 报org.apache.ibatis.binding.BindingException异常，即使xml文件中包含的内容和mapper接口中包含的语句不重复也会出错，
        // 包括加载mapper接口时自动加载的xml mapper也一样会出错。
        // 不管使用何种方式，最终都是由XMLMapperBuilder来解析Mapper.xml
        if ("package".equals(child.getName())) {
          /*
            配置样例:
            <mappers>
                <package name="org.mybatis.internal.example.mapper"/>
            </mappers>
           */
          String mapperPackage = child.getStringAttribute("name");
          // 扫描指定包下继承自Object的接口
          configuration.addMappers(mapperPackage);
        } else {
          /*
            配置样例:
            <mappers>
                <mapper resource="org/mybatis/internal/example/mapper/UserMapper.xml"/>
                <mapper url="file:///org/mybatis/internal/example/mapper/UserMapper.xml"/>
                <mapper class="org.mybatis.internal.example.mapper.UserMapper"/>
            </mappers>
           */
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          // url, resource or class 必须设置且只能设置一个
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            InputStream inputStream = Resources.getResourceAsStream(resource);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            mapperParser.parse(); // 真正解析Mapper.xml
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse(); // 真正解析Mapper.xml
          } else if (resource == null && url == null && mapperClass != null) {
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
