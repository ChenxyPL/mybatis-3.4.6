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
package org.apache.ibatis.mapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.Jdk;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class ResultMap {
  private Configuration configuration;

  private String id;
  private Class<?> type;
  private List<ResultMapping> resultMappings; // resultMap下的所有节点
  private List<ResultMapping> idResultMappings; // resultMap下的id节点 如：<id property="id" column="user_id" />
  private List<ResultMapping> constructorResultMappings; // resultMap下的constructor节点
  private List<ResultMapping> propertyResultMappings; // resultMap下的result节点 如： <result property="password" column="hashed_password"/>
  private Set<String> mappedColumns; // 映射的列名
  private Set<String> mappedProperties;  // 映射的javaBean属性名
  private Discriminator discriminator;
  private boolean hasNestedResultMaps; // 是否有嵌套resultMap
  private boolean hasNestedQueries; // 是否有嵌套子查询
  private Boolean autoMapping; // 自动映射属性

  private ResultMap() {
  }

  public static class Builder {
    private static final Log log = LogFactory.getLog(Builder.class);

    private ResultMap resultMap = new ResultMap();

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
      this(configuration, id, type, resultMappings, null);
    }

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
      resultMap.configuration = configuration;
      resultMap.id = id;
      resultMap.type = type;
      resultMap.resultMappings = resultMappings;
      resultMap.autoMapping = autoMapping;
    }

    public Builder discriminator(Discriminator discriminator) {
      resultMap.discriminator = discriminator;
      return this;
    }

    public Class<?> type() {
      return resultMap.type;
    }

    public ResultMap build() {
      if (resultMap.id == null) {
        throw new IllegalArgumentException("ResultMaps must have an id");
      }
      resultMap.mappedColumns = new HashSet<String>();
      resultMap.mappedProperties = new HashSet<String>();
      resultMap.idResultMappings = new ArrayList<ResultMapping>();
      resultMap.constructorResultMappings = new ArrayList<ResultMapping>();
      resultMap.propertyResultMappings = new ArrayList<ResultMapping>();
      final List<String> constructorArgNames = new ArrayList<String>();
      // 遍历所有resultMappings
      for (ResultMapping resultMapping : resultMap.resultMappings) {
        // 判断是否有嵌套查询, nestedQueryId是在buildResultMappingFromContext方法中通过解析节点的select属性得到的
        resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
        // 判断是否嵌套了association或者collection, nestedResultMapId是在buildResultMappingFromContext方法中通过读取节点的resultMap属性得到的或者内嵌resultMap的时候自动计算得到的。
        // 注：这里的resultSet没有地方set进来,DTD中也没有看到，不确定是不是有意预留的，但是association/collection的子元素中倒是有声明
        resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);
        // 获取column属性, 包括复合列，复合列是在org.apache.ibatis.builder.MapperBuilderAssistant.parseCompositeColumnName(String)中解析的。所有的数据库列都被按顺序添加到resultMap.mappedColumns中
        final String column = resultMapping.getColumn();
        if (column != null) {
          resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
        } else if (resultMapping.isCompositeResult()) {
          for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
            final String compositeColumn = compositeResultMapping.getColumn();
            if (compositeColumn != null) {
              resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
            }
          }
        }
        // 所有映射的属性都被按顺序添加到resultMap.mappedProperties中
        final String property = resultMapping.getProperty();
        if(property != null) {
          resultMap.mappedProperties.add(property);
        }
        // constructor中的节点
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          resultMap.constructorResultMappings.add(resultMapping);
          if (resultMapping.getProperty() != null) {
            constructorArgNames.add(resultMapping.getProperty());
          }
        } else {
          // result节点
          resultMap.propertyResultMappings.add(resultMapping);
        }
        // id节点
        if (resultMapping.getFlags().contains(ResultFlag.ID)) {
          resultMap.idResultMappings.add(resultMapping);
        }
      }
      // 如果没有声明ID属性,就把所有属性都作为ID属性
      if (resultMap.idResultMappings.isEmpty()) {
        resultMap.idResultMappings.addAll(resultMap.resultMappings);
      }
      // 根据声明的构造器参数名和类型,反射声明的类,检查其中是否包含对应参数名和类型的构造器,如果不存在匹配的构造器,就抛出运行时异常,这是为了确保运行时不会出现异常
      if (!constructorArgNames.isEmpty()) {
        final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);
        if (actualArgNames == null) {
          throw new BuilderException("Error in result map '" + resultMap.id
              + "'. Failed to find a constructor in '"
              + resultMap.getType().getName() + "' by arg names " + constructorArgNames
              + ". There might be more info in debug log.");
        }
        // 构造器参数排序
        Collections.sort(resultMap.constructorResultMappings, new Comparator<ResultMapping>() {
          @Override
          public int compare(ResultMapping o1, ResultMapping o2) {
            int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
            int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
            return paramIdx1 - paramIdx2;
          }
        });
      }
      // lock down collections
      // 为了避免用于无意或者有意事后修改resultMap的内部结构, 克隆一个不可修改的集合提供给用户
      resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
      resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
      resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
      resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
      resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
      return resultMap;
    }

    private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
      Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
      for (Constructor<?> constructor : constructors) {
        Class<?>[] paramTypes = constructor.getParameterTypes();
        if (constructorArgNames.size() == paramTypes.length) {
          List<String> paramNames = getArgNames(constructor);
          if (constructorArgNames.containsAll(paramNames)
              && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
            return paramNames;
          }
        }
      }
      return null;
    }

    private boolean argTypesMatch(final List<String> constructorArgNames,
        Class<?>[] paramTypes, List<String> paramNames) {
      for (int i = 0; i < constructorArgNames.size(); i++) {
        Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
        Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
        if (!actualType.equals(specifiedType)) {
          if (log.isDebugEnabled()) {
            log.debug("While building result map '" + resultMap.id
                + "', found a constructor with arg names " + constructorArgNames
                + ", but the type of '" + constructorArgNames.get(i)
                + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                + actualType.getName() + "]");
          }
          return false;
        }
      }
      return true;
    }

    private List<String> getArgNames(Constructor<?> constructor) {
      List<String> paramNames = new ArrayList<String>();
      List<String> actualParamNames = null;
      /*
        getParameterAnnotations()获取方法的注解数据，返回的是一个二维数组
        第一维度对应方法参数，长度等于参数个数；第二维度对应某个参数的注解，长度等于这个参数上的注解个数
       */
      final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
      int paramCount = paramAnnotations.length;
      for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
        String name = null;
        for (Annotation annotation : paramAnnotations[paramIndex]) {
          if (annotation instanceof Param) {
            name = ((Param) annotation).value();
            break;
          }
        }
        // 允许使用方法签名中的名称作为语句参数名称。 为了使用该特性，你的工程必须采用Java 8编译，并且加上-parameters选项。（从3.4.1开始）
        if (name == null && resultMap.configuration.isUseActualParamName() && Jdk.parameterExists) {
          if (actualParamNames == null) {
            actualParamNames = ParamNameUtil.getParamNames(constructor);
          }
          if (actualParamNames.size() > paramIndex) {
            name = actualParamNames.get(paramIndex);
          }
        }
        paramNames.add(name != null ? name : "arg" + paramIndex);
      }
      return paramNames;
    }
  }

  public String getId() {
    return id;
  }

  public boolean hasNestedResultMaps() {
    return hasNestedResultMaps;
  }

  public boolean hasNestedQueries() {
    return hasNestedQueries;
  }

  public Class<?> getType() {
    return type;
  }

  public List<ResultMapping> getResultMappings() {
    return resultMappings;
  }

  public List<ResultMapping> getConstructorResultMappings() {
    return constructorResultMappings;
  }

  public List<ResultMapping> getPropertyResultMappings() {
    return propertyResultMappings;
  }

  public List<ResultMapping> getIdResultMappings() {
    return idResultMappings;
  }

  public Set<String> getMappedColumns() {
    return mappedColumns;
  }

  public Set<String> getMappedProperties() {
    return mappedProperties;
  }

  public Discriminator getDiscriminator() {
    return discriminator;
  }

  public void forceNestedResultMaps() {
    hasNestedResultMaps = true;
  }
  
  public Boolean getAutoMapping() {
    return autoMapping;
  }

}
