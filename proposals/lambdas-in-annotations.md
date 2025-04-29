# Lambda expression in annotations

* **Type**: Design proposal
* **Author**: Roman Sakno
* **Contributors**: Roman Sakno
* **Status**: Under consideration
* **Prototype**: Not started

## Synopsis
Spring Framework and Java EE has many annotations used for validation of bean properties. For strings regexp is a proven way to validate the content:
```java
public class Person{
  private String mailAddress;
  
  @Validation("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$")
  public String getMailAddress(){
    return mailAddress;
  }
}
```

For validation of user data types these frameworks ask programmer to specify class which implements functional interface with validation logic:

```java
@FunctionalInterface
interface Validator<T>{
  boolean validate(T value)
}

public @interface Validation{
  Class<? extends Validator<?>> value();
}
```

To use this annotation, it is necessary to create implementation of functional interface as class and then specify it in annotation:
```java
class PersonValidator implements Validator<Person>{
  public boolean validate(Person value){
    return value != null && value.getPhoneNumber != null;
  }
}

public Company{
  private Person value;
 
  @Validation(PersonValidator::class)
  public Person getOwner(){
    return value;
  }
}
```

The same situation in Kotlin. Programmer have to write custom class with implementation of functional interface. This proposal describes syntax for describing implementation of functional interface inside of annotation in the form of lambda expression.

## Language syntax
The first extension to language grammar should allow to use lambda expression as an argument for annotation. Previous example can be rewritten in Kotlin as follows:
```kotlin
class Company{
  @Validation({it is Person && it.phoneNumber != null})
  var owner: Person? = null
}
```

Lambda expression can see only statis and companion members of enclosing class and other classes.
Lambda expression syntax is applicable to annotation argument if the following conditions are met:
1. Type of annotation parameter is `Class<? extends T>`.
1. Type `T` is functional interface in terms of Java functional interfaces (can contain many methods but only one abstract method without default implementation) **-or-** T is an abstract class with single abstract method (**protected** or **public**) and other methods with default implementation.

The second extension to language grammar allows to use functional type in annotation class parameters:
```kotlin
annotation class Validation(val validator: (Any) -> Bool)
```
Also, **reified** keyword can be applied to functional type:
```kotlin
annotation class Validation(val validator: reified (Any) -> Bool)
```

This keyword significantly changes generated code and **reified** declaration is not compatible with Java annotation parameters typed as *Class*. It means that lambda expression signature can be reified at compile time:
```kotlin
val validation = method.findAnnotation<Validation>()
validation?.validator(Person()) //because validator has actual type (Any) -> Bool, not Class<?>
```

Details will be explained in the next section.

## Compiler implementation
There are two different implementations of this proposal depending on **reified** modifier.

### Regular functional type
Compiler translates lambda expression into separated class with implementation of appropriate method so Java code still can work with such annotation value.

Given:
```kotlin
annotation class Validation(val validator: (Any) -> Bool)

class Company{
  @Validation({it is Person && it.phoneNumber != null})
  var owner: Person? = null
}
```
Translation result:
```kotlin
class Company{
  @JvmSynthetic //yes, classes in JVM can be synthetic
  class $PersonValidation1$: Validator{
    @JvmSynthetic
    override fun validate(obj: Any?) = obj is Person && it.phoneNumber != null
  }
  
  @Validation($PersonValidation1$::class)
  var owner: Person? = null
}
```

Invocation of annotation member _validator_ declared by _Validation_ annotation is not supported by compiler as stated above.

### Reified functional type
For this type of declaration Kotlin runtime must have two special annotations: *MethodReference* and *MethodSignature*. Description of these annotation demonstrated in Java as a low-level language in comparison to Kotlin to be more clean:
```java
public @interface MethodSignature{ //describes method signature for compiler control
  Class<?>[] value();
  Class<?> returnType();
}

public @interface MethodReference{ //describes method reference to the actual lambda implementation
  String methodName();
  MethodSignature signature();
  Class<?> declaringClass();
}
```
Also, we need additional extension method that can be used to resolve a method:
```kotlin
fun MethodReference.resolveMethod(): java.lang.reflect.Method = declaringClass.getDeclaredMethod(methodName, *signature)
```

Now, annotation parameter declaration can be translated easily:
```kotlin
annotation class Validation(val validator: reified (Any) -> Bool)

class Company{
  @Validation({it is Person && it.phoneNumber != null})
  var owner: Person? = null
}
```
into
```kotlin
annotation class Validation(@MethodSignature(Any::class, returnType = Bool::class) val validator: MethodReference)

class Company{
  @JvmSythetic
  @JvmStatic
  fun $personValidation$(obj: Any?) = obj is Person && obj.phoneNumber != null
  
  @Validation(validator = @MethodReference(methodName = "$personValidation$", signature = @MethodSignature(Any::class, Bool::class), declaringClass = Company::class)
  fun owner: Person? = null
}
```
That's why reified declaration gives enough guarantees about signature of lambda implementation:
```kotlin
val validation = method.findAnnotation<Validation>()
val isValid = validation?.validator(Person())
```
will be translated into
```kotlin
val validation = method.findAnnotation<Validation>()
val method = validation?.validator?.resolveMethod() //resolveMethod() is an extension in runtime library described above
val lambda = //do invokedynamic magic in conjunction with LambdaMetafactory to translate Method into functional interface instance
val isValid = lambda(Person())
```
Call safety is guaranteed because compiler recognizes *MethodSignature* annotation and can infer proper parameter types. It is possible to pass *validator* as a lambda function to another function:
```kotlin
fun bar(action: (Any?) -> Bool){
}

val validation = method.findAnnotation<Validation>()
bar(validation?.validator!!)
```

#### Optimization of call site
If reflection is too slow we can always replace this code and turn it into **invokedynamic** invocation with *MutableCallSite*. Such optimization gives JVM a chance to recognize it as monomorphic or polymorphic call and inline referenced method.
