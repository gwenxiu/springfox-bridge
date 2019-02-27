package com.github.doublebin.utils.springfox.bridge.core.builder.annotations;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BridgeGroup {
    String value() default "";
}