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
annotation class Validation(val validator: noinline (Any) -> Bool)
```
Functional type can have **noinline** modifier which instructs compiler to generate **Class** actual type instead of Kotlin intrinsic, which means that lambda cannot be inlined. This keyword is needed to separate two types of lambda expression support for annotation arguments:
1. **noinline** lambda has actual type **Class&lt;out F&gt;** which allows to be compatible and interoperable with Java code easily.
1. If **noinline** lambda is not used then annotation argument will have special annotation type *MethodReference* from Kotlin runtime (should be also added).

In simple word, ability to call annotation argument depends on this modifier. If annotation argument has **noinline** functional type then it can't be called as regular lambda. If annotation argument has functional type without **noinline** then this member can be called as follows:

```kotlin
val validation = method.findAnnotation<Validation>()
validation?.validator(Person())
```

## Compiler implementation
There are two different implementations of this proposal depending on **noinline** modifier.

### noinline functional type
Compiler translates lambda expression into separated class with implementation of appropriate method so Java code still can work with such annotation value.

Given:
```kotlin
annotation class Validation(val validator: noinline (Any) -> Bool)

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

### Regular functional type
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
fun MethodReference.resolveMethod(): java.lang.reflect.Method = declaringClass.getDeclaredMethod(methodName, signature)
```

Now, annotation parameter declaration can be translated easily:
```kotlin
annotation class Validation(val validator: (Any) -> Bool)

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
That's why in this version of declaration (without **noinline**) calling of annotation member *validator* is applicable because compiler has enough guarantees about accessibility of lambda implementation:
```kotlin
val validation = method.findAnnotation<Validation>()
val isValid = validation?.validator(Person())
```
will be translated into
```kotlin
val validation = method.findAnnotation<Validation>()
val method = validation?.validator?.resolveMethod() //resolveMethod() is an extension in runtime library described above
val isValid = method.invoke(Person()) as Bool
```
Call safety is guaranteed because compiler recognizes *MethodSignature* annotation and can infer proper parameter types.

#### Optimization of call site
If reflection is too slow we can always replace this code and turn it into **invokedynamic** invocation with *MutableCallSite*. Such optimization gives JVM a chance to recognize it as monomorphic or polymorphic call and inline referenced method.
