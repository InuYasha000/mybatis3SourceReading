/**
 * Copyright 2009-2017 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.builder.xml;

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
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

/**
 * XML 配置构建器，主要负责解析 mybatis-config.xml 配置文件
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    /**
     * 是否已解析
     */
    private boolean parsed;
    /**
     * 基于 Java XPath 解析器
     */
    private final XPathParser parser;
    /**
     * 环境
     */
    private String environment;
    /**
     * ReflectorFactory 对象
     */
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
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
        // 创建 Configuration 对象
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        // 设置 Configuration 的 variables 属性
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    /**
     * 解析 XML 成 Configuration 对象。
     *
     * @return Configuration 对象
     */
    public Configuration parse() {
        // 若已解析，抛出 BuilderException 异常
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        // 标记已解析
        parsed = true;
        // 解析 XML configuration 节点
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    /**
     * 解析 XML
     *
     * 具体 MyBatis 有哪些 XML 标签，参见 《XML 映射配置文件》http://www.mybatis.org/mybatis-3/zh/configuration.html
     *
     * @param root 根节点
     */
    private void parseConfiguration(XNode root) {
        try {
            //issue #117 read properties first
            // 解析 <properties /> 标签
            propertiesElement(root.evalNode("properties"));
            // 解析 <settings /> 标签,检查变量是否有对应set方法
            Properties settings = settingsAsProperties(root.evalNode("settings"));
            // 加载自定义的 VFS 实现类，设置到 BaseBuilder.configuration 变量中
            loadCustomVfs(settings);
            // 解析 <typeAliases /> 标签，将配置类注册到 typeAliasRegistry 中
            typeAliasesElement(root.evalNode("typeAliases"));
            // 解析 <plugins /> 标签，添加到 Configuration#interceptorChain 中,拦截器链条
            pluginElement(root.evalNode("plugins"));
            // 解析 <objectFactory /> 标签,设置到 Configuration.objectFactory 中
            objectFactoryElement(root.evalNode("objectFactory"));
            // 解析 <objectWrapperFactory /> 标签，设置到 Configuration.objectWrapperFactory 中
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
            // 解析 <reflectorFactory /> 标签，设置到 Configuration.reflectorFactory 中
            reflectorFactoryElement(root.evalNode("reflectorFactory"));
            // 赋值 <settings /> 到 Configuration 属性，上面那个setting只是检查了变量是否有set方法，这里才是真正赋值操作
            settingsElement(settings);
            // read it after objectFactory and objectWrapperFactory issue #631
            // 解析 <environments /> 标签
            environmentsElement(root.evalNode("environments"));
            // 解析 <databaseIdProvider /> 标签，拿到数据库标识并且设置到 Configuration.databaseId 中
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));
            // 解析 <typeHandlers /> 标签,注册逻辑
            typeHandlerElement(root.evalNode("typeHandlers"));
            // 解析 <mappers /> 标签,注册到 Configuration.MapperRegistry.knownMappers 中
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    /**
     * 将 <setting /> 标签解析为 Properties 对象
     * <settings>
     *   <setting name="cacheEnabled" value="true"/>
     *   <setting name="lazyLoadingEnabled" value="true"/>
     *   <setting name="multipleResultSetsEnabled" value="true"/>
     *   <setting name="useColumnLabel" value="true"/>
     *   <setting name="useGeneratedKeys" value="false"/>
     *   <setting name="autoMappingBehavior" value="PARTIAL"/>
     *   <setting name="autoMappingUnknownColumnBehavior" value="WARNING"/>
     *   <setting name="defaultExecutorType" value="SIMPLE"/>
     *   <setting name="defaultStatementTimeout" value="25"/>
     *   <setting name="defaultFetchSize" value="100"/>
     *   <setting name="safeRowBoundsEnabled" value="false"/>
     *   <setting name="mapUnderscoreToCamelCase" value="false"/>
     *   <setting name="localCacheScope" value="SESSION"/>
     *   <setting name="jdbcTypeForNull" value="OTHER"/>
     *   <setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/>
     * </settings>
     *
     * @param context 节点
     * @return Properties 对象
     */
    private Properties settingsAsProperties(XNode context) {
        // 将子标签，解析成 Properties 对象
        if (context == null) {
            return new Properties();
        }
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class
        // 校验每个属性，在 Configuration 中，有相应的 setting 方法，否则抛出 BuilderException 异常
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    /**
     * 加载自定义 VFS 类
     *
     * @param props Properties 对象
     * @throws ClassNotFoundException 当 VFS 类不存在时
     */
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        // 获得 vfsImpl 属性
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            // 使用 , 作为分隔符，拆成 VFS 类名的数组
            String[] clazzes = value.split(",");
            // 遍历 VFS 类名的数组
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    // 获得 VFS 类
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    // 设置到 Configuration 中
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    /**
     * 解析 <typeAliases /> 标签，将配置类注册到 {@link org.apache.ibatis.type.TypeAliasRegistry} 中。
     * <typeAliases>
     *   <typeAlias alias="Author" type="domain.blog.Author"/>
     *   <typeAlias alias="Blog" type="domain.blog.Blog"/>
     *   <typeAlias alias="Comment" type="domain.blog.Comment"/>
     *   <typeAlias alias="Post" type="domain.blog.Post"/>
     *   <typeAlias alias="Section" type="domain.blog.Section"/>
     *   <typeAlias alias="Tag" type="domain.blog.Tag"/>
     * </typeAliases>
     *
     * @param parent 节点
     */
    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                // 指定为包的情况下，注册包下的每个类
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                // 指定为类的情况下，直接注册类和别名
                } else {
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        Class<?> clazz = Resources.classForName(type); // 获得类是否存在
                        // 注册到 typeAliasRegistry 中
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) { // 若类不存在，则抛出 BuilderException 异常
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * 解析 <plugins /> 标签，添加到 {@link Configuration#interceptorChain} 中
     * <plugins>
     *   <plugin interceptor="org.mybatis.example.ExamplePlugin">
     *     <property name="someProperty" value="100"/>
     *   </plugin>
     * </plugins>
     *
     * @param parent 节点
     * @throws Exception 发生异常时
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            // 遍历 <plugins /> 标签
            for (XNode child : parent.getChildren()) {
                String interceptor = child.getStringAttribute("interceptor");
                Properties properties = child.getChildrenAsProperties();
                // 创建 Interceptor 对象，并设置属性
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                interceptorInstance.setProperties(properties);
                // 添加到 configuration 中
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    /**
     * <objectFactory type="org.mybatis.example.ExampleObjectFactory">
     *   <property name="someProperty" value="100"/>
     * </objectFactory>
     * @param context
     * @throws Exception
     */
    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ObjectFactory 的实现类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties properties = context.getChildrenAsProperties();
            // 创建 ObjectFactory 对象，并设置 Properties 属性
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            factory.setProperties(properties);
            // 设置 Configuration 的 objectFactory 属性
            configuration.setObjectFactory(factory);
        }
    }

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ObjectWrapperFactory 的实现类
            String type = context.getStringAttribute("type");
            // 创建 ObjectWrapperFactory 对象
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            // 设置 Configuration 的 objectWrapperFactory 属性
            configuration.setObjectWrapperFactory(factory);
        }
    }

    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ReflectorFactory 的实现类
            String type = context.getStringAttribute("type");
            // 创建 ReflectorFactory 对象
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            // 设置 Configuration 的 reflectorFactory 属性
            configuration.setReflectorFactory(factory);
        }
    }

    /**
     * 1. 解析 <properties /> 标签，成 Properties 对象。
     * 2. 覆盖 configuration 中的 Properties 对象到上面的结果。
     * 3. 设置结果到 parser 和 configuration 中
     *
     * <properties resource="org/mybatis/example/config.properties">
     *   <property name="username" value="dev_user"/>
     *   <property name="password" value="F2Fa3!33TYyg"/>
     * </properties>
     *
     * @param context 节点
     * @throws Exception 解析发生异常
     */
    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            // 读取子标签们，为 Properties 对象
            Properties defaults = context.getChildrenAsProperties();
            // 读取 resource 和 url 属性
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");
            if (resource != null && url != null) { // resource 和 url 都存在的情况下，抛出 BuilderException 异常
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            // 读取本地 Properties 配置文件到 defaults 中。
            if (resource != null) {
                defaults.putAll(Resources.getResourceAsProperties(resource));
                // 读取远程 Properties 配置文件到 defaults 中。
            } else if (url != null) {
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            // 覆盖 configuration 中的 Properties 对象到 defaults 中。
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }
            // 设置 defaults 到 parser 和 configuration 中。
            parser.setVariables(defaults);
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
        Class<? extends TypeHandler> typeHandler = resolveClass(props.getProperty("defaultEnumTypeHandler"));
        configuration.setDefaultEnumTypeHandler(typeHandler);
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        @SuppressWarnings("unchecked")
        Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * <environments default="development">
     *   <environment id="development">
     *     <transactionManager type="JDBC">
     *       <property name="..." value="..."/>
     *     </transactionManager>
     *     <dataSource type="POOLED">
     *       <property name="driver" value="${driver}"/>
     *       <property name="url" value="${url}"/>
     *       <property name="username" value="${username}"/>
     *       <property name="password" value="${password}"/>
     *     </dataSource>
     *   </environment>
     * </environments>
     * @param context
     * @throws Exception
     */
    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            // environment 属性非空，从 default 属性获得
            if (environment == null) {
                environment = context.getStringAttribute("default");
            }
            // 遍历 XNode 节点
            for (XNode child : context.getChildren()) {
                // 判断 environment 是否匹配
                String id = child.getStringAttribute("id");
                if (isSpecifiedEnvironment(id)) {
                    // 解析 `<transactionManager />` 标签，返回 TransactionFactory 对象
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    // 解析 `<dataSource />` 标签，返回 DataSourceFactory 对象
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    DataSource dataSource = dsFactory.getDataSource();
                    // 创建 Environment.Builder 对象
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    // 构造 Environment 对象，并设置到 configuration 中
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    /**
     * <databaseIdProvider type="DB_VENDOR">
     *   <property name="SQL Server" value="sqlserver"/>
     *   <property name="DB2" value="db2"/>
     *   <property name="Oracle" value="oracle" />
     * </databaseIdProvider>
     * 这个方法就是去拿数据库商业名字，然后在这个properties中看有没有匹配上，比如SQL SERVER 那么返回sqlserver，否则返回null
     * @param context
     * @throws Exception
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            // 获得 DatabaseIdProvider 的类
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility 保持兼容
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // 获得 Properties 对象
            Properties properties = context.getChildrenAsProperties();
            // 创建 DatabaseIdProvider 对象，并设置对应的属性
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            // 获得对应的 databaseId 编号
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            // 设置到 configuration 中
            configuration.setDatabaseId(databaseId);
        }
    }

    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 TransactionFactory 的类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();
            // 创建 TransactionFactory 对象，并设置属性
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 DataSourceFactory 的类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();
            // 创建 DataSourceFactory 对象，并设置属性
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     * <typeHandlers>
     *   <typeHandler handler="org.mybatis.example.ExampleTypeHandler"/>
     * </typeHandlers>
     * @param parent
     * @throws Exception
     */
    private void typeHandlerElement(XNode parent) throws Exception {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                // 如果是 package 标签，则扫描该包
                if ("package".equals(child.getName())) {
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                // 如果是 typeHandler 标签，则注册该 typeHandler 信息
                } else {
                    // 获得 javaType、jdbcType、handler
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName); // 非空
                    // 注册 typeHandler
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

    /**
     * <!-- 使用相对于类路径的资源引用 -->
     * <mappers>
     *   <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
     *   <mapper resource="org/mybatis/builder/BlogMapper.xml"/>
     *   <mapper resource="org/mybatis/builder/PostMapper.xml"/>
     * </mappers>
     * <!-- 使用完全限定资源定位符（URL） -->
     * <mappers>
     *   <mapper url="file:///var/mappers/AuthorMapper.xml"/>
     *   <mapper url="file:///var/mappers/BlogMapper.xml"/>
     *   <mapper url="file:///var/mappers/PostMapper.xml"/>
     * </mappers>
     * <!-- 使用映射器接口实现类的完全限定类名 -->
     * <mappers>
     *   <mapper class="org.mybatis.builder.AuthorMapper"/>
     *   <mapper class="org.mybatis.builder.BlogMapper"/>
     *   <mapper class="org.mybatis.builder.PostMapper"/>
     * </mappers>
     * <!-- 将包内的映射器接口实现全部注册为映射器 -->
     * <mappers>
     *   <package name="org.mybatis.builder"/>
     * </mappers>
     * @param parent
     * @throws Exception
     */
    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                // 如果是 package 标签，则扫描该包
                if ("package".equals(child.getName())) {
                    // 获得包名
                    String mapperPackage = child.getStringAttribute("name");
                    // 添加到 configuration 中
                    configuration.addMappers(mapperPackage);
                // 如果是 mapper 标签，
                } else {
                    // 获得 resource、url、class 属性
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    // 使用相对于类路径的资源引用
                    if (resource != null && url == null && mapperClass == null) {
                        ErrorContext.instance().resource(resource);
                        // 获得 resource 的 InputStream 对象
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        // 创建 XMLMapperBuilder 对象
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                        // 执行解析
                        mapperParser.parse();
                    // 使用完全限定资源定位符（URL）
                    } else if (resource == null && url != null && mapperClass == null) {
                        ErrorContext.instance().resource(url);
                        // 获得 url 的 InputStream 对象
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        // 创建 XMLMapperBuilder 对象
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        // 执行解析
                        mapperParser.parse();
                    // 使用映射器接口实现类的完全限定类名
                    } else if (resource == null && url == null && mapperClass != null) {
                        // 获得 Mapper 接口
                        Class<?> mapperInterface = Resources.classForName(mapperClass);
                        // 添加到 configuration 中
                        configuration.addMapper(mapperInterface);
                    } else {
                        throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    /**
     * 判断是否为匹配的环境
     *
     * @param id 环境编号
     * @return 是否匹配
     */
    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) { // 相等
            return true;
        }
        return false;
    }

}
