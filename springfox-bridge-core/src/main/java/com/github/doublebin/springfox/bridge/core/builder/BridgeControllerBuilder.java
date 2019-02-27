package com.github.doublebin.springfox.bridge.core.builder;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.doublebin.springfox.bridge.core.builder.annotations.BridgeApi;
import com.github.doublebin.springfox.bridge.core.builder.annotations.BridgeOperation;
import com.github.doublebin.springfox.bridge.core.exception.BridgeException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.ParameterAnnotationsAttribute;
import javassist.bytecode.annotation.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import com.github.doublebin.springfox.bridge.core.util.ReflectUtil;
import com.github.doublebin.springfox.bridge.core.util.StringUtil;
import com.github.doublebin.springfox.bridge.core.util.JavassistUtil;

@Slf4j
public class BridgeControllerBuilder {

    private static ConcurrentHashMap<String, AtomicInteger> classNameMap = new ConcurrentHashMap<String, AtomicInteger>();

    private static ConcurrentHashMap<String, AtomicInteger> methodNameMap = new ConcurrentHashMap<String, AtomicInteger>();

    public static String classFilePath = "/temp/springfox-bridge";

    private static final String NEW_CLASS_NAME_PRE = "swagger.bridge.controllers.Swagger";

    private static final ClassPool pool = ClassPool.getDefault();

    public static Class newControllerClass(Class oldClass){

        if (ReflectUtil.hasAnnotationAtClass(oldClass, BridgeApi.class)){

            CtClass newControllerCtClass = pool.makeClass(buildNewClassName(oldClass));

            try {
                CtField beanCtField = addAndGetBeanField(oldClass, newControllerCtClass);

                addAutowiredAnnotationAtField(beanCtField);

                Method[] allMethods = oldClass.getMethods();
                for(Method method: allMethods){
                    if(ReflectUtil.hasAnnotationAtMethod(method, BridgeOperation.class)){
                        String methodName = method.getName();

                        Class requestBodyClass = BridgeRequestBuilder.newRequestClass(method,oldClass.getSimpleName()+ StringUtil.toCapitalizeCamelCase(methodName)+"Request");

                        CtMethod newCtMethod = addAndGetHomonymicMethod(method, newControllerCtClass, requestBodyClass);

                        addAnnotationsAtMethod(newCtMethod, method);

                        addAnnotationsAtMethodParameters(newCtMethod);
                    }
                }

                addAnnotationsAtClass(newControllerCtClass, oldClass);

                newControllerCtClass.writeFile(classFilePath);
                return newControllerCtClass.toClass();

            } catch (CannotCompileException|IOException e) {
                log.error("New controller class for old class [{}] failed.", oldClass.getName(), e);
                throw new BridgeException("New controller class failed", e);
            }
        }

        log.error("New controller class for old class [{}] failed, old class has no BridgeApi annotation.", oldClass.getName());
        throw new BridgeException(oldClass.getName() + " has no BridgeApi annotation, cannot new controller class.");
    }

    private static CtMethod addAndGetHomonymicMethod(Method method, CtClass newControllerCtClass, Class requestBodyClass) {
        try {
            String methodName = method.getName();
            Class returnType = method.getReturnType();
            Parameter[] parameters = method.getParameters();

            CtMethod newCtMethod = new CtMethod(pool.get(returnType.getName()), methodName,
                    null == requestBodyClass ? new CtClass[] {} : new CtClass[] {pool.get(requestBodyClass.getName())}, newControllerCtClass);
            newCtMethod.setModifiers(Modifier.PUBLIC);

            int size = parameters.length;

            String body = "{return this.bean." + methodName + "(";
            for (int i = 0; i < size; i++) {

                body += ("$1.getParam" + i + "()"); //$1 means the first parameter
                if (i != size - 1) {
                    body += ",";
                }
            }

            body += ");}";

            newCtMethod.setBody(body);
            newControllerCtClass.addMethod(newCtMethod);
            return newCtMethod;

        } catch (Exception e){
            log.error("Add new homonymic method failed.", e);
            throw new BridgeException("Add new homonymic method failed.", e);
        }
    }

    private static void addAnnotationsAtMethod(CtMethod newCtMethod, Method method){
        ConstPool constpool = newCtMethod.getDeclaringClass().getClassFile().getConstPool();
        AnnotationsAttribute methodAttr = new AnnotationsAttribute(constpool, AnnotationsAttribute.visibleTag);

        addRequestMappingAnnotationAtMethod(methodAttr, method, constpool);

        addApiOperationAnnotationAtMethod(methodAttr, method, constpool);

        MethodInfo methodInfo = newCtMethod.getMethodInfo();
        methodInfo.addAttribute(methodAttr);
    }


    private static void addApiOperationAnnotationAtMethod(AnnotationsAttribute methodAttr, Method method, ConstPool constpool){

        String[] annotationMethodNames = new String[]{"value", "notes", "tags", "response", "responseContainer", "responseReference",
                "httpMethod", "produces", "consumes", "protocols", "hidden", "responseHeaders", "code", "extensions"};

        Annotation apiOperationAnnotation = JavassistUtil.copyAnnotationValues(method, BridgeOperation.class, ApiOperation.class, constpool, annotationMethodNames);

        methodAttr.addAnnotation(apiOperationAnnotation);
    }

    private static void addRequestMappingAnnotationAtMethod(AnnotationsAttribute methodAttr, Method method, ConstPool constpool){
        Annotation requestMappingAnnotation = new Annotation(RequestMapping.class.getName(), constpool);

        Parameter[] parameters = method.getParameters();
        String requestMappingValue = "/"+method.getName();
        if(ArrayUtils.isNotEmpty(parameters)){
            requestMappingValue += "/";

            String suffix = "";
            for(Parameter parameter: parameters){
                Class parameterType = parameter.getType();
                String typeName = parameterType.getSimpleName();
                suffix += typeName.substring(0,1);
            }

            requestMappingValue += suffix;

            String methodNameIncludeSuffix = method.getDeclaringClass().getName()+"."+method.getName()+"."+suffix;
            methodNameMap.putIfAbsent(methodNameIncludeSuffix, new AtomicInteger(0));
            int count = methodNameMap.get(methodNameIncludeSuffix).incrementAndGet();

            if (count > 1) {
                requestMappingValue += "/" + (count - 1); //去重
            }
        }



        MemberValue[] values = new StringMemberValue[]{new StringMemberValue(requestMappingValue, constpool)};
        ArrayMemberValue arrayStringMemberValue = new ArrayMemberValue(constpool);
        arrayStringMemberValue.setValue(values);
        requestMappingAnnotation.addMemberValue("value", arrayStringMemberValue);

        EnumMemberValue methodEnumberValue = new EnumMemberValue(constpool);
        methodEnumberValue.setType(RequestMethod.class.getName());
        methodEnumberValue.setValue("POST");
        ArrayMemberValue arrayEnumMemberValue = new ArrayMemberValue(constpool);
        MemberValue[] enumValues = new EnumMemberValue[]{methodEnumberValue};
        arrayEnumMemberValue.setValue(enumValues);
        requestMappingAnnotation.addMemberValue("method", arrayEnumMemberValue);

        methodAttr.addAnnotation(requestMappingAnnotation);
    }

    private static void addAnnotationsAtMethodParameters(CtMethod newCtMethod){
        ConstPool constpool = newCtMethod.getDeclaringClass().getClassFile().getConstPool();
        ParameterAnnotationsAttribute parameterAnnotationsAttribute = new ParameterAnnotationsAttribute(constpool, ParameterAnnotationsAttribute.visibleTag);
        Annotation[][] paramArrays = new Annotation[1][1];
        Annotation[] addAnno = new Annotation[1];
        addAnno[0] =  new Annotation(RequestBody.class.getName(), constpool);
        paramArrays[0] = addAnno;
        parameterAnnotationsAttribute.setAnnotations(paramArrays);
        newCtMethod.getMethodInfo().addAttribute(parameterAnnotationsAttribute);
    }

    private static void addTagsMember(Annotation apiAnnotation, Class oldClass, ConstPool constpool){
        String[] tags = (String[])ReflectUtil.getAnnotationValue(oldClass, BridgeApi.class, "tags");
        List<StringMemberValue> tagsList = new ArrayList<StringMemberValue>();
        tagsList.add(new StringMemberValue(oldClass.getName(), constpool));
        if(ArrayUtils.isNotEmpty(tags)){
            for(String tag : tags){
                if (StringUtil.isNoneBlank(tag)) {
                    tagsList.add(new StringMemberValue(tag, constpool));
                }
            }
        }
        ArrayMemberValue tagArrayMemberValue = new ArrayMemberValue(constpool);
        tagArrayMemberValue.setValue(tagsList.toArray(new MemberValue[0]));
        apiAnnotation.addMemberValue("tags", tagArrayMemberValue);
    }

    private static void addAnnotationsAtClass(CtClass newControllerCtClass, Class oldClass) {
        ClassFile ccFile = newControllerCtClass.getClassFile();
        ConstPool constpool = ccFile.getConstPool();

        AnnotationsAttribute classAttr = new AnnotationsAttribute(constpool, AnnotationsAttribute.visibleTag);
        Annotation restControllerAnnotation = new Annotation(RestController.class.getName(), constpool);
        classAttr.addAnnotation(restControllerAnnotation);


        String[] annotationMethodNames = new String[]{"value", "description", "tags", "coverAll", "produces", "consumes", "protocols", "hidden"};
        Annotation apiAnnotation = JavassistUtil.copyAnnotationValues(oldClass, BridgeApi.class, Api.class, constpool, annotationMethodNames);
        addTagsMember(apiAnnotation, oldClass, constpool);
        classAttr.addAnnotation(apiAnnotation);

        Annotation requestMappingAnnotation = new Annotation(RequestMapping.class.getName(), constpool);
        MemberValue[] values = new StringMemberValue[]{new StringMemberValue("/"+oldClass.getName(), constpool)};
        ArrayMemberValue arrayMemberValue = new ArrayMemberValue(constpool);
        arrayMemberValue.setValue(values);
        requestMappingAnnotation.addMemberValue("value", arrayMemberValue);
        classAttr.addAnnotation(requestMappingAnnotation);

        ccFile.addAttribute(classAttr);
    }

    private static String buildNewClassName(Class oldClass) {
        String orignClassName = NEW_CLASS_NAME_PRE + oldClass.getSimpleName();
        classNameMap.putIfAbsent(orignClassName, new AtomicInteger(0));
        int count = classNameMap.get(orignClassName).incrementAndGet();

        String newClassName = orignClassName;
        if (count > 1) {
            newClassName = orignClassName + (count - 1);
        }

        return newClassName;
    }

    private static void addAutowiredAnnotationAtField(CtField beanCtField) {
        ConstPool constpool = beanCtField.getDeclaringClass().getClassFile().getConstPool();
        Annotation autowiredAnnotation = new Annotation(Autowired.class.getName(), constpool);
        JavassistUtil.addAnnotationForCtField(beanCtField, autowiredAnnotation);
    }

    private static CtField addAndGetBeanField(Class oldClass, CtClass newControllerCtClass) {
        try {
            CtField beanCtField = new CtField(pool.get(oldClass.getName()),  "bean", newControllerCtClass);
            beanCtField.setModifiers(Modifier.PRIVATE);
            newControllerCtClass.addField(beanCtField); //添加属性
            return beanCtField;
        } catch (Exception e){
            log.error("Add bean property failed for new class {}, bean type is {}.",newControllerCtClass.getName(), oldClass.getName(), e);
            throw new BridgeException("Add bean property failed for new class " + newControllerCtClass.getName(), e);
        }

    }

    public static void main(String[] args) throws Exception {

    }
}
