package com.yankaizhang.spring.context.generic;

import com.yankaizhang.spring.beans.BeanDefinition;
import com.yankaizhang.spring.beans.BeanDefinitionRegistry;
import com.yankaizhang.spring.beans.factory.CompletedBeanFactory;
import com.yankaizhang.spring.beans.factory.config.BeanPostProcessor;
import com.yankaizhang.spring.beans.factory.impl.DefaultBeanFactory;
import com.yankaizhang.spring.context.support.AbstractApplicationContext;
import com.yankaizhang.spring.context.util.BeanDefinitionRegistryUtils;
import com.yankaizhang.spring.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

/**
 * 通用上下文对象<br/>
 * 拥有所有上下文对象的通用操作 - 处理Bean定义
 * @author dzzhyk
 * @since 2020-12-20 18:06:33
 */
public class GenericApplicationContext extends AbstractApplicationContext implements BeanDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(GenericApplicationContext.class);

    /** 真正的beanFactory对象 */
    private final DefaultBeanFactory beanFactory;

    public GenericApplicationContext() {
        this.beanFactory = new DefaultBeanFactory();
    }

    public GenericApplicationContext(DefaultBeanFactory beanFactory) {
        Assert.notNull(beanFactory, "用于初始化的beanFactory不能为null");
        this.beanFactory = beanFactory;
    }

    @Override
    public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition) throws Exception {
        BeanDefinitionRegistryUtils.registerBeanDefinition(beanFactory, beanName, beanDefinition);
    }

    @Override
    public void removeBeanDefinition(String beanName) throws Exception {
        beanFactory.removeBeanDefinition(beanName);
    }

    @Override
    public BeanDefinition getBeanDefinition(String beanName) throws Exception {
        return beanFactory.getBeanDefinition(beanName);
    }

    @Override
    public boolean containsBeanDefinition(String beanName) {
        return beanFactory.containsBeanDefinition(beanName);
    }

    @Override
    public String[] getBeanDefinitionNames() {
        return beanFactory.getBeanDefinitionNames();
    }

    @Override
    public int getBeanDefinitionCount() {
        return beanFactory.getBeanDefinitionCount();
    }

    @Override
    public boolean isBeanNameInUse(String beanName) {
        return beanFactory.isBeanNameInUse(beanName);
    }

    @Override
    public CompletedBeanFactory getBeanFactory() throws IllegalStateException {
        return this.beanFactory;
    }

    @Override
    public void close() {
        destroyBeans();
    }

    public List<BeanPostProcessor> getBeanPostProcessors(){
        return beanFactory.getBeanPostProcessors();
    }

    @Override
    public Map<String, BeanDefinition> getBeanDefinitionMap() {
        return beanFactory.getBeanDefinitionMap();
    }

}
