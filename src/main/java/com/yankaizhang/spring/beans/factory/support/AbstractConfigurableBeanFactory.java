package com.yankaizhang.spring.beans.factory.support;

import com.yankaizhang.spring.beans.BeanDefinition;
import com.yankaizhang.spring.beans.factory.BeanFactory;
import com.yankaizhang.spring.beans.factory.FactoryBean;
import com.yankaizhang.spring.beans.factory.ObjectFactory;
import com.yankaizhang.spring.beans.factory.config.BeanPostProcessor;
import com.yankaizhang.spring.beans.factory.ConfigurableBeanFactory;
import com.yankaizhang.spring.beans.factory.config.InstantiationAwareBeanPostProcessor;
import com.yankaizhang.spring.beans.factory.impl.RootBeanDefinition;
import com.yankaizhang.spring.beans.holder.BeanWrapper;
import com.yankaizhang.spring.util.Assert;
import com.yankaizhang.spring.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 顶层的抽象bean工厂实现，提供了默认的bean工厂方法，包括配置方法
 * @author dzzhyk
 * @since 2020-12-21 18:31:52
 */
public abstract class AbstractConfigurableBeanFactory implements ConfigurableBeanFactory {

    private static final Logger log = LoggerFactory.getLogger(AbstractConfigurableBeanFactory.class);

    /** 父类工厂对象 */
    private BeanFactory parentBeanFactory;

    /**
     * 单例IoC容器
     * 这个容器一般存放扫描到的Bean单例类对象
     */
    private Map<String, Object> singletonIoc = new ConcurrentHashMap<>();

    /**
     * 单例工厂容器
     * 这个容器存放可能的bean单例的工厂对象，通过单例工厂可以创建得到一个单例对象
     */
    private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

    /**
     * 通用的IoC容器
     * 最终使用的一般是这个通用的IoC容器
     * 这个容器中的所有Bean对象应该都是经过增强的包装Bean
     */
    private Map<String, BeanWrapper> commonIoc = new ConcurrentHashMap<>();

    /** bean处理器 */
    private final List<BeanPostProcessor> beanPostProcessors = new CopyOnWriteArrayList<>();

    /** 初始化bean前置处理器 */
    private final List<InstantiationAwareBeanPostProcessor> instantiationAware = new ArrayList<>();


    @Override
    public BeanFactory getParentBeanFactory() {
        return this.parentBeanFactory;
    }

    @Override
    public Object getBean(String beanName) throws RuntimeException  {
        return doGetBean(beanName, null);
    }

    @Override
    public <T> T getBean(Class<T> beanClass) throws RuntimeException  {
        return doGetBean(null, beanClass);
    }

    @Override
    public <T> T getBean(String beanName, Class<T> beanClass) throws RuntimeException  {
        return doGetBean(beanName, beanClass);
    }

    /**
     * 真正的获取bean对象函数
     * 1. 首先检查是否已经创建了单例对象
     * 2. 如果存在父类工厂，向父类工厂发起检查doGetBean
     * 3. 如果没有父类工厂，获取该beanName对应的bean定义，创建一个新的bean实例
     * @param beanName  bean对象名称
     * @return bean对象 (可能为null)
     */
    @SuppressWarnings("all")
    protected <T> T doGetBean(String beanName, Class<T> beanClass, Object... args) throws RuntimeException {

        Object bean;

        // 检查是否已经有了实例化好的单例bean，或者使用单例工厂获取一个工厂
        Object singletonObject = getSingleton(beanName);
        if (singletonObject != null){
            bean = singletonObject;

        }else{

            // 单例没找到，检查是否有父类bean工厂
            BeanFactory parentBeanFactory = getParentBeanFactory();
            // 如果有父类并且本容器中没有bean定义
            if (parentBeanFactory != null && !containsBeanDefinition(beanName)){
                // 去父类找
                if (parentBeanFactory instanceof AbstractConfigurableBeanFactory) {
                    return ((AbstractConfigurableBeanFactory) parentBeanFactory).doGetBean(beanName, beanClass);
                } else if (beanClass != null) {
                    // 如果父类不是AbstractConfigurableBeanFactory抽象类的实现，需要特别判断beanClass!=null的情况
                    return parentBeanFactory.getBean(beanName, beanClass);
                } else {
                    return (T) parentBeanFactory.getBean(beanName);
                }
            }

            // 没有可创建单例，没有父类，只能自己创建一个bean实例对象了
            // 获取到的可能不是RootBeanDefinition类型，但是在创建的时候需要统一使用RootBeanDefinition
            BeanDefinition temp = getBeanDefinition(beanName);
            RootBeanDefinition beanDefinition = new RootBeanDefinition(temp);
            String[] dependsOn = beanDefinition.getDependsOn();

            // 检查依赖情况
            if (dependsOn != null) {
                for (String dep : dependsOn) {
                    // 检查是否有循环依赖，否则会死循环
                    BeanDefinition depBeanDef = getBeanDefinition(dep);
                    String[] defDependsOn = depBeanDef.getDependsOn();
                    for (String depdep : defDependsOn) {
                        if (depdep.equals(beanName)){
                            throw new RuntimeException("存在循环依赖 => [" + beanName + " <=> " + dep + " ]");
                        }
                    }
                    try {
                        getBean(dep);
                    } catch (RuntimeException ex) {
                        throw new RuntimeException("获取" + beanName + "的依赖bean失败 => " + dep);
                    }
                }
            }

            if (beanDefinition.isSingleton()){
                // 这里调用子类实现的createBean方法创建完整的单例对象，
                Object wrappedBean = getSingleton(beanName, () -> createBean(beanName, beanDefinition, args));
                bean = getObjectForBeanInstance(wrappedBean, beanName);
            }else if (beanDefinition.isPrototype()){
                Object wrappedBean = createBean(beanName, beanDefinition, args);
                bean = getObjectForBeanInstance(wrappedBean, beanName);
            }else{
                throw new RuntimeException("目前只支持创建单例、多例对象 => " + beanName);
            }

//            BeanPostProcessor beanPostProcessor = new BeanPostProcessor();
//
//            // 实例化原始bean对象
//            Object instance = instantiateBean(beanDefinition);
//            if (null == instance) return null;
//
//            // 前置处理
//            Object bean = beanPostProcessor.postProcessBeforeInitialization(instance, beanName);
//
//            // 执行定义的init-method
//            invokeInitMethods(bean, beanDefinition);
//
//            // 后置处理
//            bean = beanPostProcessor.postProcessAfterInitialization(bean, beanName);
//
//            // 生成BeanWrapper增强对象
//            BeanWrapper beanWrapper = new BeanWrapper(bean);
//
//            this.commonIoc.put(beanName, beanWrapper);  // beanName可以找到这个实例
//
//            // 返回实例化的bean包装类，等待注入
//            return beanWrapper;
        }

        // 最后检查bean实例是否满足beanClass的要求

        return (T) bean;
    }

    /**
     * 现在拿到手里的已经是准备完成的、前后置处理完成的bean对象
     * 这个bean对象可能是一个工厂类对象，所以在Spring里面，如果尝试获取一个工厂类bean对象，会返回他的创建后对象
     * 这个方法就是用来判断工厂对象，如果是工厂对象，返回工厂的产品
     * @param wrappedBean 原始bean对象
     * @param beanName bean名称
     * @return 创建好的bean对象
     */
    private Object getObjectForBeanInstance(Object wrappedBean, String beanName) {
        if (wrappedBean instanceof BeanWrapper){
            final Object wrappedInstance = ((BeanWrapper) wrappedBean).getWrappedInstance();
            // 如果是工厂对象
            if (wrappedInstance instanceof FactoryBean){
                try {
                    Object object = ((FactoryBean) wrappedInstance).getObject();
                    return object;
                } catch (Exception e) {
                    log.error("获取工厂对象的产品失败 => {}", beanName);
                    e.printStackTrace();
                }
            }
        }
        return wrappedBean;
    }
//
//    /**
//     * 从mergedBeanDefinitions和子类的beanDefinitions中获取可能的RootBeanDefinition
//     */
//    protected RootBeanDefinition getMergedLocalBeanDefinition(String beanName) {
//        RootBeanDefinition beanDefinition = this.mergedBeanDefinitions.get(beanName);
//        if (beanDefinition != null){
//            return beanDefinition;
//        }
//        return getMergedBeanDefinition(beanName, getBeanDefinition(beanName));
//    }
//
//    /**
//     * 从子类获取的beanDefinition中创建得到RootBeanDefinition
//     */
//    private RootBeanDefinition getMergedBeanDefinition(String beanName, BeanDefinition beanDefinition) {
//        synchronized (this.mergedBeanDefinitions) {
//            RootBeanDefinition mbd = null;
//            RootBeanDefinition previous = null;
//            // 二次校验
//            mbd = this.mergedBeanDefinitions.get(beanName);
//            if (mbd == null){
//                previous = mbd;
//                // 如果该bean定义没有父定义，直接返回一个RootBeanDefinition即可
//                if (beanDefinition.getParentName() == null){
//                    return new RootBeanDefinition(beanDefinition);
//                }
//            }else{
//                // 如果该bean定义有父定义，需要和父定义合并
//                BeanDefinition pbd;
//                String parentName = beanDefinition.getParentName();
//                if (!beanName.equals(parentName)){
//                    pbd = getMergedBeanDefinition(parentName);
//                }
//            }
//
//            return mbd;
//        }
//    }

    /**
     * 在容器中获取可能的已经初始化的对象
     * @param beanName bean名称
     * @return 可能的已创建的对象
     */
    private Object getSingleton(String beanName){
         Object singletonObject = this.singletonIoc.get(beanName);
         if (singletonObject == null){
             synchronized (this.singletonIoc) {
                 singletonObject = this.singletonIoc.get(beanName);
                 if (singletonObject == null) {
                     // 获取可能的单例工厂对象
                     ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
                     if (singletonFactory != null) {
                         singletonObject = singletonFactory.getObject();
                         this.singletonFactories.remove(beanName);
                     }
                 }
             }
         }
        return singletonObject;
//
//        // 优先使用beanName进行查找
//        if (beanName != null){
//            // 如果有类型指定，判断是否为当前类型
//            if (result != null && beanClass != null){
//                if (result.getClass().equals(beanClass)){
//                    return result;
//                }
//                throw new Exception("未找到beanName为" + beanName +"，类型为"+ beanClass.getName() +"的对象");
//            }else if (result != null){
//                return result;
//            }
//            return null;
//        }
//
//        // beanName为null或者使用name没找到的情况，尝试使用beanClass寻找
//        if (beanClass != null){
//            // beanClass不为null
//            Collection<BeanDefinition> values = this.beanDefinitionMap.values();
//
//            // 在bean定义中对比，查找是否有这个类的定义
//            for (BeanDefinition value : values) {
//                if (value.getBeanClassName().equals(beanClass.getName())){
//                    // 如果找到了该类的bean定义，就尝试在容器中找该类的实例
//                    for (Map.Entry<String, BeanWrapper> entry : commonIoc.entrySet()) {
//
//                        // 如果找到了
//                        if (entry.getValue().getWrappedClass().equals(beanClass)) {
//                            if (beanName != null && !beanName.equals(entry.getKey())){
//                                throw new Exception("未找到beanName= " + beanName + "的bean实例");
//                            }
//                            return entry.getValue().getWrappedInstance();
//                        }
//                    }
//                }
//            }
//            return null;
//        }
//
//        throw new Exception("beanName 与 beanClass 均为null，找不到Bean实例");
    }

    /**
     * 使用指定的工厂对象创建单例对象
     * @param beanName bean名称
     * @param singletonFactory 指定的单例工厂对象
     * @return 创建好的单例对象
     */
    private Object getSingleton(String beanName, ObjectFactory<?> singletonFactory){
        Assert.notNull(beanName, "beanName不能为null");
        synchronized (this.singletonIoc) {
            Object singletonObject = this.singletonIoc.get(beanName);
            if (singletonObject == null) {
                log.debug("创建单例对象 : {}", beanName);
                boolean newSingleton = false;
                try {
                    singletonObject = singletonFactory.getObject();
                    newSingleton = true;
                }
                catch (RuntimeException ex) {
                    singletonObject = this.singletonIoc.get(beanName);
                    if (singletonObject == null) {
                        throw ex;
                    }
                }
                // 如果创建成功，加入容器
                if (newSingleton) {
                    synchronized (this.singletonIoc) {
                        this.singletonIoc.put(beanName, singletonObject);
                    }
                }
            }
            return singletonObject;
        }
    }

    /**
     * 执行bean的定义好的初始化函数
     * @param bean bean对象
     * @param beanDefinition bean对象的bean定义
     */
    private void invokeInitMethods(Object bean, BeanDefinition beanDefinition) {
        String initMethodName = beanDefinition.getInitMethodName();
        if (!StringUtils.isEmpty(initMethodName)){
            try {
                Method initMethod = bean.getClass().getMethod(initMethodName);
                initMethod.invoke(bean, null);
                log.debug("执行 : "+ bean.getClass() +"对象的初始化方法 : " + initMethod.getName());
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
                log.warn("获取 : "+ bean.getClass() +"对象的初始化方法失败");
            } catch (IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
                log.warn("执行 : " + bean.getClass() + "对象的初始化方法失败");
            }
        }
    }


    @Override
    public boolean containsBean(String beanName) {
        return false;
    }

    @Override
    public boolean isSingleton(String beanName) throws Exception {
        return false;
    }

    @Override
    public void setParentBeanFactory(BeanFactory beanFactory) {

    }

    @Override
    public void addBeanPostProcessor(BeanPostProcessor beanPostProcessor) {

    }

    @Override
    public int getBeanPostProcessorCount() {
        return this.beanPostProcessors.size();
    }

    @Override
    public void destroyBean(String beanName, Object beanInstance) {

    }

    /*
        下面是子类必须要实现的抽象方法
     */

    protected abstract boolean containsBeanDefinition(String beanName);

    protected abstract BeanDefinition getBeanDefinition(String beanName) throws RuntimeException;

    protected abstract Object createBean(String beanName, RootBeanDefinition mbd, Object[] args) throws RuntimeException;


    public List<InstantiationAwareBeanPostProcessor> getInstantiationAware() {
        return instantiationAware;
    }

    protected boolean hasInstantiationAwareBeanPostProcessors() {
        return instantiationAware.isEmpty();
    }
}
