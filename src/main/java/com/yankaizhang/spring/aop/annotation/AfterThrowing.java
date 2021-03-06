package com.yankaizhang.spring.aop.annotation;

import java.lang.annotation.*;

/**
 * 异常通知
 * @author dzzhyk
 * @since 2020-11-28 13:53:38
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AfterThrowing {

    String exception() default "java.lang.Exception";

}