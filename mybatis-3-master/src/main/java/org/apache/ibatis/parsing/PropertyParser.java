/**
 * Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.parsing;

import java.util.Properties;

/**
 * 动态属性解析器
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class PropertyParser {

    private static final String KEY_PREFIX = "org.apache.ibatis.parsing.PropertyParser.";
    /**
     * The special property key that indicate whether enable a default value on placeholder.
     * <p>
     *   The default value is {@code false} (indicate disable a default value on placeholder)
     *   If you specify the {@code true}, you can specify key and default value on placeholder (e.g. {@code ${db.username:postgres}}).
     * </p>
     * @since 3.4.2
     */
    public static final String KEY_ENABLE_DEFAULT_VALUE = KEY_PREFIX + "enable-default-value";

    /**
     * The special property key that specify a separator for key and default value on placeholder.
     * <p>
     *   The default separator is {@code ":"}.
     * </p>
     * @since 3.4.2
     */
    public static final String KEY_DEFAULT_VALUE_SEPARATOR = KEY_PREFIX + "default-value-separator";

    private static final String ENABLE_DEFAULT_VALUE = "false";
    private static final String DEFAULT_VALUE_SEPARATOR = ":";

    private PropertyParser() {
        // Prevent Instantiation
    }

    // 基于 variables 替换动态值，如果 result 为动态值

    /**
     * <dataSource type="POOLED">
     *   <property name="driver" value="${driver}"/>
     *   <property name="url" value="${url}"/>
     *   <property name="username" value="${username}"/>
     *   <property name="password" value="${password}"/>
     * </dataSource>
     *
     * variables 的来源，即可以在常用的 Java Properties 文件中配置，也可以使用 MyBatis <property /> 标签中配置
     * 这里配置的 username 和 password 属性，就可以替换上面的 ${username} 和 ${password} 这两个动态属性
     * <properties resource="org/mybatis/example/config.properties">
     *   <property name="username" value="dev_user"/>
     *   <property name="password" value="F2Fa3!33TYyg"/>
     * </properties>
     */
    public static String parse(String string, Properties variables) {
        // 创建 VariableTokenHandler 对象
        VariableTokenHandler handler = new VariableTokenHandler(variables);
        // 创建 GenericTokenParser 对象
        // openToken = { ，closeToken = } ，这就是上面看到的 ${username} 和 {password}
        GenericTokenParser parser = new GenericTokenParser("${", "}", handler);
        // 执行解析
        return parser.parse(string);
    }

    /**
     * 变量 Token 处理器
     */
    private static class VariableTokenHandler implements TokenHandler {
        /**
         * <properties resource="org/mybatis/example/config.properties">
         *   <property name="org.apache.ibatis.parsing.PropertyParser.enable-default-value" value="true"/>
         *   <property name="org.apache.ibatis.parsing.PropertyParser.default-value-separator" value="?:"/> 分隔符被修改成了 ?:
         * </properties>
         */


        /**
         * 变量 Properties 对象
         */
        private final Properties variables;
        /**
         * 是否开启默认值功能。默认为 {@link #ENABLE_DEFAULT_VALUE}
         */
        private final boolean enableDefaultValue;
        /**
         * 默认值的分隔符。默认为 {@link #KEY_DEFAULT_VALUE_SEPARATOR} ，即 ":" 。
         */
        private final String defaultValueSeparator;

        private VariableTokenHandler(Properties variables) {
            this.variables = variables;
            this.enableDefaultValue = Boolean.parseBoolean(getPropertyValue(KEY_ENABLE_DEFAULT_VALUE, ENABLE_DEFAULT_VALUE));
            this.defaultValueSeparator = getPropertyValue(KEY_DEFAULT_VALUE_SEPARATOR, DEFAULT_VALUE_SEPARATOR);
        }

        private String getPropertyValue(String key, String defaultValue) {
            return (variables == null) ? defaultValue : variables.getProperty(key, defaultValue);
        }

        @Override
        public String handleToken(String content) {
            if (variables != null) {
                String key = content;
                // 开启默认值功能
                if (enableDefaultValue) {
                    // 查找默认值
                    final int separatorIndex = content.indexOf(defaultValueSeparator);
                    String defaultValue = null;
                    if (separatorIndex >= 0) {
                        key = content.substring(0, separatorIndex);
                        defaultValue = content.substring(separatorIndex + defaultValueSeparator.length());
                    }
                    // 有默认值，优先替换，不存在则返回默认值
                    if (defaultValue != null) {
                        return variables.getProperty(key, defaultValue);
                    }
                }
                // 未开启默认值功能，直接替换
                if (variables.containsKey(key)) {
                    return variables.getProperty(key);
                }
            }
            // 无 variables ，直接返回
            return "${" + content + "}";
        }

    }

}
