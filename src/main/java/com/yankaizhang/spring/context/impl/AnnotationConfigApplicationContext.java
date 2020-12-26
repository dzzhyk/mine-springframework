package com.yankaizhang.spring.context.impl;

import com.yankaizhang.spring.aop.holder.AopConfig;
import com.yankaizhang.spring.aop.AopProxy;
import com.yankaizhang.spring.aop.impl.CglibAopProxy;
import com.yankaizhang.spring.aop.impl.JdkDynamicAopProxy;
import com.yankaizhang.spring.aop.annotation.Aspect;
import com.yankaizhang.spring.aop.annotation.PointCut;
import com.yankaizhang.spring.aop.support.AdviceSupportComparator;
import com.yankaizhang.spring.aop.support.AdvisedSupport;
import com.yankaizhang.spring.aop.support.AopAnnotationReader;
import com.yankaizhang.spring.aop.support.AopUtils;
import com.yankaizhang.spring.beans.BeanDefinition;
import com.yankaizhang.spring.beans.holder.BeanWrapper;
import com.yankaizhang.spring.beans.factory.annotation.Autowired;
import com.yankaizhang.spring.beans.factory.config.BeanFactoryPostProcessor;
import com.yankaizhang.spring.beans.factory.config.BeanPostProcessor;
import com.yankaizhang.spring.context.AnnotationConfigRegistry;
import com.yankaizhang.spring.context.annotation.Configuration;
import com.yankaizhang.spring.context.annotation.Controller;
import com.yankaizhang.spring.context.annotation.Service;
import com.yankaizhang.spring.context.config.AnnotatedBeanDefinitionReader;
import com.yankaizhang.spring.context.config.ClassPathBeanDefinitionScanner;
import com.yankaizhang.spring.context.config.ConfigClassReader;
import com.yankaizhang.spring.context.generic.GenericApplicationContext;
import com.yankaizhang.spring.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.yankaizhang.spring.util.StringUtils.toLowerCase;

/**
 * 真正使用到的IOC容器，直接接触用户
 * 继承了DefaultListableBeanFactory，并且把里面的refresh方法实现了
 * 实现了BeanFactory接口，实现了getBean()方法
 * 完成IoC、DI、AOP的衔接
 * AnnotationConfigApplicationContext是专门用来解析注解配置类的容器对象
 *
 * 实现了AnnotationConfigRegistry接口，说明拥有基本的两个方法scan和register
 *
 * TODO: 这里其实对于AnnotationConfigApplicationContext而言，在这一层实现了多个功能，其实是简化过后的
 * @author dzzhyk
 * @since 2020-12-02 14:55:28
 */
@SuppressWarnings("all")
public class AnnotationConfigApplicationContext extends GenericApplicationContext implements AnnotationConfigRegistry {

    private static final Logger log = LoggerFactory.getLogger(AnnotationConfigApplicationContext.class);

    private AnnotatedBeanDefinitionReader reader;
    private ClassPathBeanDefinitionScanner scanner;

    /**
     * 配置类解析器
     * TODO: 这个应该使用前置处理器机制替代
     */
    private ConfigClassReader configClassReader;
    /**
     * aop注解reader
     * TODO: 目前还没有更好的替代方案
     */
    private AopAnnotationReader aopAnnotationReader;

    /**
     * 通用的AOP切面容器
     * TODO: 这个容器不应该存在，但是还没有想出很好的办法
     */
    private List<Class<?>> aspectBeanInstanceCache = new CopyOnWriteArrayList<>();

    /**
     * beanFactoryPostProcessor列表
     */
    private List<BeanFactoryPostProcessor> beanFactoryPostProcessors = new ArrayList<>();

    /**
     * beanPostProcessors列表
     */
    private List<BeanPostProcessor> beanPostProcessors = new CopyOnWriteArrayList<>();

    public AnnotationConfigApplicationContext() {
        this.reader = new AnnotatedBeanDefinitionReader(this);
        this.scanner = new ClassPathBeanDefinitionScanner(this);
    }

    /**
     * 从其他容器创建容器
     * @param context
     */
    public AnnotationConfigApplicationContext(AnnotationConfigApplicationContext context){
        this();
        this.commonIoc = context.getCommonIoc();
        this.singletonIoc = context.getSingletonIoc();
    }

    /**
     * 传入配置类初始化容器
     */
    public AnnotationConfigApplicationContext(Class<?>... configClass){
        this();
        register(configClass);
        refresh();
    }

    /**
     * 传入待扫描路径初始化容器
     */
    public AnnotationConfigApplicationContext(String... basePackages){
        this();
        scan(basePackages);
        refresh();
    }

    @Override
    public void refresh() {
        // 1. 先处理配置类的bean定义 - 相当于前置处理
        // TODO: 改为前置处理器实现
        doProcessAnnotationConfiguration();
        // 2. 将剩余bean定义实例化
        doInstance();
        // 3. 处理AOP对象
        doAop();
        // 4. 处理依赖注入
        doAutowired();
    }

    /**
     * 预先处理配置类的bean定义
     * Spring中这里使用的是一个BeanDefinitionRegisterPostProcessor来完成的
     */
    private void doProcessAnnotationConfiguration() {

        configClassReader = new ConfigClassReader(this, scanner);

        try {
            for (Map.Entry<String, BeanDefinition> entry : this.beanDefinitionMap.entrySet()) {
                BeanDefinition definition = entry.getValue();
                Class<?> configClazz = Class.forName(definition.getBeanClassName());
                // 如果是配置类，就解析该配置类下的@Bean注册内容
                if (configClazz.isAnnotationPresent(Configuration.class)){
                    configClassReader.parseAnnotationConfigClass(configClazz);
                }
            }

        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 处理Aop切面
     */
    public void doAop(){

        postProcessAspects();
        List<AdvisedSupport> aopConfigs = aopAnnotationReader.parseAspect();    // 解析所有已知切面类，获取包装类的config

        if (aopConfigs != null){

            // 排序aopConfigs，按照类别深度从深到浅
            aopConfigs.sort(new AdviceSupportComparator());

            // 转变每个aopConfig为多个，目标类分别对应目录下的类
            for (AdvisedSupport aopConfig : aopConfigs) {
                List<String> targetClassList = aopConfig.parseClasses();    // 获取这个切面可以切到的目标类
                for (String clazzName : targetClassList) {
                    if (commonIoc.containsKey(clazzName)){
                        // 只处理容器中有的组件
                        BeanWrapper beanWrapper = commonIoc.get(clazzName);
                        Object wrappedInstance = beanWrapper.getWrappedInstance();
                        Class<?> wrappedClass = beanWrapper.getWrappedClass();  // 虽然可能是代理类，但是Class一定是代理类的最终目标类

                        AdvisedSupport myConfig = null;
                        try {
                            // 这里将原有的aopConfig为每个代理类扩增，防止java内存赋值
                            myConfig = getProxyAopConfig(aopConfig, wrappedInstance, wrappedClass);
                        }catch (Exception e){
                            e.printStackTrace();
                        }

                        if (myConfig.pointCutMatch()){
                            Object proxy = createProxy(myConfig).getProxy();
                            beanWrapper.setWrappedInstance(proxy);  // 将这个二次代理对象包装起来

                            log.debug("为"+ AopUtils.getAopTarget(proxy).getSimpleName() +"创建代理对象 : " + proxy.getClass());
                            commonIoc.replace(clazzName, beanWrapper);       // 重新设置commonIoC中的对象为代理对象
                            String beanName = toLowerCase(clazzName.substring(clazzName.lastIndexOf(".") + 1));
                            commonIoc.replace(beanName, beanWrapper);    // 同时更新beanName对应的实例

                            // 如果这个类有接口，同时更新这些接口的实现类对象
                            for (Class<?> anInterface : wrappedClass.getInterfaces()) {
                                String interfaceName = anInterface.getName();
                                String iocBeanInterfaceName = toLowerCase(interfaceName.substring(interfaceName.lastIndexOf(".") + 1));
                                commonIoc.replace(iocBeanInterfaceName, beanWrapper);   // 对于接口，只需要更新其beanName对应的实例，因为beanClass对应的实例已经更新过了
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 根据已有的aopConfig得到新的aopConfig实例，同时设置TargetClass
     */
    private AdvisedSupport getProxyAopConfig(AdvisedSupport advisedSupport, Object wrappedInstance, Class<?> wrappedClass)
            throws Exception {
        AopConfig myAopConfig = advisedSupport.getAopConfig().clone();
        AdvisedSupport myAdviceSupport = new AdvisedSupport(myAopConfig);
        myAdviceSupport.setTarget(wrappedInstance);
        myAdviceSupport.setTargetClass(wrappedClass);
        return myAdviceSupport;
    }

    /**
     * 预处理所有已知切面类，收集切面表达式，创建AopAnnotationReader对象
     */
    private void postProcessAspects() {

        HashMap<Class<?>, String> map = new HashMap<>();

        for (Class<?> aspectClazz : aspectBeanInstanceCache) {

            Method[] aspectClazzMethods = aspectClazz.getDeclaredMethods();

            // 加载切点类PointCut
            List<Method> aspectMethods = new ArrayList<>(aspectClazzMethods.length);
            Collections.addAll(aspectMethods, aspectClazzMethods);

            for (Method method : aspectMethods) {
                if (method.isAnnotationPresent(PointCut.class)){
                    // 如果定义了切点类，保存切点表达式
                    String execution = method.getAnnotation(PointCut.class).value().trim();
                    map.put(aspectClazz, execution);
                    break;
                }
            }
        }
        aopAnnotationReader = new AopAnnotationReader();
        aopAnnotationReader.setPointCutMap(map);
    }

    /**
     * 把非懒加载的类提前初始化
     */
    private void doInstance() {
        for (Map.Entry<String, BeanDefinition> beanDefinitionEntry : super.beanDefinitionMap.entrySet()) {
            String beanName = beanDefinitionEntry.getKey(); // 首字母小写的类名
            BeanDefinition beanDefinition = beanDefinitionEntry.getValue();
            // 如果不是懒加载，初始化bean
            if (!beanDefinition.isLazyInit()){
                try {
                    getBean(beanName);
                    if (log.isDebugEnabled()){
                        log.debug("初始化bean对象 : " + beanName);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 依赖注入
     */
    private void doAutowired() {
        try {
            for (Map.Entry<String, BeanWrapper> entry : commonIoc.entrySet()) {
                Object instance = entry.getValue().getWrappedInstance();
                // 依赖注入
                populateBean(entry.getKey(), instance);
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }




    /**
     * 对bean实例属性进行依赖注入
     * 目前只支持注入Service和Controller类型
     */
    private void populateBean(String beanName, Object instance) throws Exception{
        Class<?> clazz = instance.getClass();
        if (!(clazz.isAnnotationPresent(Controller.class) ||
            clazz.isAnnotationPresent(Service.class))){
            return;
        }

        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (!field.isAnnotationPresent(Autowired.class)) continue;
            Autowired autowired = field.getAnnotation(Autowired.class);

            // 获取待注入bean的beanName   - 改为全类名
            String autowiredBeanName = autowired.value().trim();
            if ("".equals(autowiredBeanName)){
                // 如果没有，找到这个注解标记的属性的全类名，注入全类名
                Class<?> type = field.getType();
                autowiredBeanName = toLowerCase(type.getSimpleName());
            }

            field.setAccessible(true);
            BeanWrapper toAutowiredInstanceWrapper = null;
            // 默认使用name进行自动装配
            toAutowiredInstanceWrapper = this.commonIoc.get(autowiredBeanName);

            if (null == toAutowiredInstanceWrapper){
                // 如果没有找到按照name装配的bean，寻找按照type装配的bean
                autowiredBeanName = field.getName();
                toAutowiredInstanceWrapper = this.commonIoc.get(autowiredBeanName);
            }
            if (null == toAutowiredInstanceWrapper){
                throw new Exception("commonsIoC容器未找到相应的bean对象 ==> " + autowiredBeanName);
            }
            Object wrappedInstance = toAutowiredInstanceWrapper.getWrappedInstance();
            if (null == wrappedInstance){
                throw new Exception("BeanWrapper代理instance对象不存在 ==> " + autowiredBeanName);
            }

            // 将获取的包装类对象装配上去
            field.set(instance, wrappedInstance);
        }
    }


    /**
     * 创建代理对象
     */
    private AopProxy createProxy(AdvisedSupport config){
        if (config.getTargetClass().getInterfaces().length > 0){
            return new JdkDynamicAopProxy(config);  // 如果对象存在接口，默认使用jdk动态代理
        }
        return new CglibAopProxy(config);       // 如果对象没有接口，使用CGLib动态代理
    }

    public Map<String, Object> getSingletonIoc() {
        return singletonIoc;
    }
    public Map<String, BeanWrapper> getCommonIoc() {
        return commonIoc;
    }

    @Override
    public void register(Class<?>... componentClasses) {
        this.reader.register(componentClasses);
    }

    @Override
    public void scan(String... basePackages) {
        this.scanner.scan(basePackages);
    }

    public List<BeanFactoryPostProcessor> getBeanFactoryPostProcessors() {
        return this.beanFactoryPostProcessors;
    }

    public List<BeanPostProcessor> getBeanPostProcessors() {
        return this.beanPostProcessors;
    }


    /**
     * 向当前容器中添加BeanFactoryPostProcessor
     * @param processor
     */
    public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor processor){
        this.beanFactoryPostProcessors.remove(processor);
        this.beanFactoryPostProcessors.add(processor);
    }

    /**
     * 向当前容器中添加BeanPostProcessor
     * @param postProcessor
     */
    public void addBeanPostProcessor(BeanPostProcessor postProcessor){
        this.beanPostProcessors.remove(postProcessor);
        this.beanPostProcessors.add(postProcessor);
    }
}
