# Implementation by delegation enhancements
* **Type**: Design proposal
* **Author**: Dico Karssiens
* **Contributors**: Daniil Vodopian
* **Status**: Submitted
* **Prototype**: Not implemented, but I would be happy to do so

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/155).  
Prior progress of this proposal is held in [this gist](https://gist.github.com/Dico200/0065293bcd9a19c50e371cde047a9f22).

##### Links
* [KT-83](https://youtrack.jetbrains.com/issue/KT-83)
* [KT-293](https://youtrack.jetbrains.com/issue/KT-293)
* [Suggestions for Interface delegation](https://discuss.kotlinlang.org/t/suggestions-for-interface-delegation/6624)
* [Unneeded limitations of delegated interfaces](https://discuss.kotlinlang.org/t/unneeded-limitations-of-delegated-interfaces/4535)
* [Accessing interface delegate](https://discuss.kotlinlang.org/t/accessing-interface-delegate/2068)
* [Delegate to class member](https://discuss.kotlinlang.org/t/delegate-to-class-member/711)

## Summary
The current behaviour of Implementation by delegation has a number of limitations.  
There are also situations where behaviour might be unexpected.  
The aim of this proposal is to provide an alternative to the existing syntax, which offers better semantics.  
We detail the current problems and some approaches for tackling them along with their respective pros and cons.  
The old syntax may be deprecated depending on the chosen approach.

## Glossary
| Term | Meaning |
| ---- | ------- |
| Implementation By Delegation | kotlin feature, subject of this KEEP |
| Delegate Identity | delegate instance, the reference to the delegate object |
| Delegate Expression | *Delegate Expression*, following `by` keyword, with the *Delegate Identity* as its result |
| Current/Old behaviour | The current behaviour of Implementation By Delegation, at the time of creating the proposal |
| Current/Old syntax | refers to `by <expression>` syntax of the Old behaviour |
| New behaviour | The behaviour of Implementation By Delegation as proposed and defined at the bottom of *Approach* section |
| Delegate Accessor | Getter function that can be called to access the *Delegate Identity* of a particular interface specific to that function |

## Description
See *Summary*.

Each approach aims to satisfy these requirements:
1. Introduce a method to change the behaviour of delegated interfaces to the *New Behaviour*;
Its syntax should be distinguishable from the old syntax under all circumstances for source backward compatibility.  
Among others, this allows for the old behaviour and syntax to be deprecated in source, if that's desirable.
1. Introduce a method to access the *Delegate Identity* from source. Small bonus if it works for the old behaviour too.

The *New Behaviour* aims to have (almost) the same semantics as kotlin properties and should be defined (roughly) like this (all in contrast to the current behaviour):
* The *Delegate Expression*, wherever it is declared, is evaluated on every invocation of a delegated method.
* The *Delegate Expression* has `this` in its scope
* No invisible fields are generated
* The *Delegate Expression* cannot refer to constructor parameters, use class members instead (programmer should store the delegate if it should be stored)  
    - An exception to this would be when a property is used and an expression is assigned to it, instead of its getter. 
    The *Delegate Expression* is in the getter, which (probably) uses the default implementation of returning the stored value.

## Motivation
Kotlin provides Implementation By Delegation as a no-boilerplate way of implementing the Delegation/Decorator pattern,
which is a flexible alternative to implementation inheritance ([see doc](https://kotlinlang.org/docs/reference/delegation.html)).

At the time when that feature was added, it was implemented such that:
* *Delegate Expression*s are evaluated once, on construction, no exceptions.
* On the JVM, the *Delegate Identity* is stored in an invisible field, where it cannot be accessed by the programmer. 
* Overriding individual delegated methods requires using a constructor parameter property to access the *Delegate Identity*.
* *Delegate Expression*s cannot refer to `this` instance at all. Instead, they can only access constructor parameters on top of outer scope.

These are considerable limitations which are not necessary, with the side effect of making certain things very difficult or obnoxious to do.

There are other constructs that aim to simplify implementation of the Delegation/Decorator pattern:
* [`ForwardingObject`](https://google.github.io/guava/releases/19.0/api/docs/com/google/common/collect/ForwardingObject.html) and subclasses from guava;  
it does not suffer from these limitations as it declares a method `protected abstract Object delegate();` which grants full control with respect to if and how it's cached.
* [`@Delegate`](https://projectlombok.org/features/Delegate.html) annotation from project lombok;  
which declares the delegation inside of the class scope, on a JVM field, and grants the same control, except that it can't run code upon access.

### Use cases
Any case where the programmer would want to:
* Access the *Delegate Identity* (proposal 2)
* Implement how to store the *Delegate Identity*
* Change the *Delegate Identity*
* Compute the *Delegate Identity* on every invocation
* Refer to `this` instance in the *Delegate Expression*
* Use Implementation By Delegation in inline classes  
This doesn't work in the old behaviour because it implicitly adds an (invisible) field, making the class exceed the one property constraint.

### Accessing Delegate Identity from source
With the old behaviour of Implementation By Delegation, the *Delegate Identity* is stored in an invisible field.
This invisible field cannot be accessed normally by the programmer, however, there are many cases where the programmer would need the *Delegate Identity*:
* When overriding the behaviour of a delegated method, but still delegating to the same object.  
* When controlling the state of the delegate if it is a stateful object.
* Using the *Delegate Identity* in a context where it's just an object with a type.

#### Current Workaround
In order to access the *Delegate Identity*, with the old behaviour:
* The *Delegate Identity* must be passed to the primary constructor as a parameter, as only primary constructor parameters are accessible within the scope of *Delegate Expression*s.
* The parameter holding the *Delegate Identity* must be stored in an explicitly declared property (by declaring the parameter as a property or storing it elsewhere explicitly).

This means that:
* The class itself does not have any control over how the delegate is instantiated, unless the constructor parameter uses a default value.
* **It requires a primary constructor parameter!!**.
* 2 distinct fields are used to store the delegate.
* If the property storing a delegate reference is mutable, mutating it does not change the *Delegate Identity*, but the programmer might think it does.
* The delegate can never have a reference to `this`, the delegating object, on instantiation.
* Code that wants to instantiate the class needs to pass the delegate itself to the constructor.
This is frequently the intended, but not always. Workarounds include: Secondary constructor, companion object invoke() overload.

There should be a language construct to get the *Delegate Identity* for a given delegated interface.  
It is accessible to the generated delegating interface methods, it should also be accessible in the source code as a solution to these problems.

I want to stress that this is NOT a problem for existing binaries.  
The delegate instance should NOT be made accessible outside the class scope through whichever solution, as it would break encapsulation,
and code that uses existing binaries is implicitly outside the class scope. 

## Approaches
We are considering 3 approaches:
1. Using different syntax
1. Moving declaration inside the class body
1. Adding contextual indication  

In the examples below, `target` refers to a property of class `Proxy`

---
### 1. Using different syntax
The alternatives are grouped as follows for simplification of *Pros* and *Cons* sections:
1. Adding a contextual keyword
    - `class Proxy : List<Int> by val target` (may be expected to declare a new property that stores `target`)
    - `class Proxy : List<Int> by volatile target` (ambiguous meaning of `volatile`)
1. Using a different keyword
    - `class Proxy : List<Int> to target`
1. Adding a character
    - `class Proxy : List<Int> by &target`
1. Full on different syntax
    - `class Proxy : List<Int> where `  
    followed by 1 or more of the below, separated by commas:
      * `List<Int> delegates target`
      * `List<Int> delegates this.target`
      * `List<Int> delegates <expression with class scope>`  
        It might be best to require direct reference to a property to borrow its semantics.
    
Requirement 2 is filled by using the *Global Delegate Accessor Function* detailed in the appendix.  
When this proposal is used, it can live alongside the old syntax peacefully without too much confusion.  
Just an extra keyword that makes the old feature offer different semantics, which is easy to explain.  
In the case of [4], this doesn't apply. Old syntax should be deprecated.

#### Pros
* Similar to old syntax (except for [4])
* It's clear to the compiler and programmer which behaviour is expected
* In the case of [2]:
    - Syntax doesn't borrow `by` keyword, so it doesn't *incorrectly* highlight that those are similar concepts
* Declaration is at the top of the class

#### Cons
* Allows for a given class to use a mix of the two behaviours
* No intuitive or explicit (using `override` keyword) way to override the *Delegate Expression* 
unless a property is referenced directly
* Permits `this` access outside class brackets
* In the case of [3]:
    - Meaning of `&` is unclear, and unfamiliar for programmers that don't have experience in certain languages.
    - Special characters are limited. Before using them, we should carefully evaluate that there isn't a better use.

---
### 2. Moving declaration inside class body
2 options are considered.  
They both make use of the `delegate` keyword, and could be implemented together for flexibility.

#### Option 1: `delegate val`
Add a contextual keyword to indicate that a property is a `delegate` of its interface type:

```kotlin
class Proxy(target: List<Int>): List<Int> { 
    delegate val listDelegate: List<Int> = target
}
```

The `delegate` contextual keyword:
* Does not interfere with the property declaration at all, with the exception that it might introduce a type restriction.
* In other words, the property can still be `inline`, `abstract`, `private`, mutable, be volatile, use a property delegate, and/or declare a getter/setter. You get the point.
* Is a lot like [`@Delegate`](https://projectlombok.org/features/Delegate.html) annotation from project lombok,  
with slightly different semantics and less complexity 

Possible type policies of a `delegate` property include:
1. the type is restricted to the interface types implemented by the class (mimicking old behaviour's effective policy)
1. the type is restricted such that there is always 1 common interface implemented by the type and the declaring class
1. the type is not restricted, and all its public members, except for those declared in `Any`, are delegated
1. the syntax can declare one (or optionally multiple) delegated interface type immediately following `delegate `,  
   Then the property type is only restricted to implement all those types, like [2], but more explicit.

Requirement 2 is filled automatically by the addition of a property.  
It would be very confusing for this approach to live alongside the old syntax. It should be deprecated.  
The *Delegate Expression* in this approach is defined as the contents of the property's getter.  

##### Pros 
* **Delegation is declared inside the class body**, a much more sensible place because:
    - Delegation is an implementation detail
    - It's where you always have `this` in the scope
* Does not make use of *Global Delegate Accessor Function* to fill requirement 2
* Same semantics as regular kotlin properties, so it's simple and intuitive to use
* Inheritance of the delegate property also has the same semantics as regular kotlin properties, 
overriding the *Delegate Expression* is the same as overriding the property, so we borrow the existing language semantics, making it more intuitive.
* Doesn't litter the class declaration line with implementation details

##### Cons
* We add a new language feature, with a syntax completely different to the old syntax
* Allows for a given class to use a mix of the two behaviours
* Confusing with property delegates? They are still a completely different concept...

The policy for delegated interface member collisions should probably be as follows:
* If the colliding member is from the supertype, and it is final, the delegation is illegal
* Otherwise, it should be overridden.
* Else (if the member is declared in the same class), that member takes priority
* And the programmer can use `super` call to prevent the delegate overriding the behavior of a member from the supertype.

#### Option 2. `delegate Interface to expression`
Add contextual keywords to indicate that an interface is `delegate` `to` an expression.

```kotlin
class Proxy : List<Int> { 
    delegate List<Int> to emptyList()
}
```

Optionally, `delegate List<Int> by lazy { listOf(1) }` can be implemented as well, allowing the use of property delegates with the same syntax.

##### Pros vs Option 1
* It is possible to declare the delegate anonymously
* Avoids boilerplate for the common case where a delegate is declared anonymously

##### Cons vs Option 1
* Can't access the Delegate Identity because of anonymity
* Can't override the Delegate Expression because of anonymity
* Also adds `to` contextual keyword

This option can also have exactly the same semantics as Option 1 if it delegates directly `to` a property's getter.

---
### 3. Adding Contextual Indication
This approach allows the programmer to indicate to the compiler that the class should have its interface delegates implemented using the new behaviour.
To indicate this, an annotation should be used. For example, the following declaration can be added to the standard library (name TBD):

```kotlin
@Target(CLASS, FILE)
@Retention(SOURCE)
public annotation class NewInterfaceDelegates
```

Requirement 2 is filled by using the *Global Delegate Accessor Function* detailed in the appendix.  
When using this approach, the old syntax must stay. It is not possible to deprecate without making breaking changes.  

#### Pros
* Doesn't add a new syntax/feature
* Doesn't allow both behaviours to be present on a given class
* Doesn't break backward compatibility because the programmer explicitly changes behaviour by declaring the annotation

#### Cons
* An annotation is used to change behaviour and semantics of a language feature significantly
* Annotation needs to be present on all classes using the new behaviour unless a compiler argument is used, which could break backward compatibility
* No intuitive or explicit (using `override` keyword) way to override the *Delegate Expression*
* No clear path has been found to deprecate and possibly phase out the old behaviour, without potentially breaking source backward compatibility.  
A compiler argument could change the behaviour for a class without the programmer knowing, so that idea was dropped.

### Dropped Ideas
* Modifying the form of the delegation expression
    - `class B : A by ::b`
    - `class B : A by { b }`
    - these would clash with existing delegations of functional types
    - compiler could behave differently when it infers type of `KProperty`, but this is implicit and confusing

## JVM Codegen
Each delegated interface type will be accompanied by an accessor function, called to access the *Delegate Identity*.
Each approach definition states individually what the accessor function is or how it should be generated.
Each interface method should be delegated to the *Delegate Identity* as returned by a call to the accessor function for that interface.

## Reflection
Generated delegate accessor functions should not be returned as part of `KClass.members`, etc,
which is the same policy used for the generated, invisible, fields as generated by the old behaviour (On the JVM)

## JS
I don't know.

## Appendix

### Global Delegate Accessor Function
These are details of a particular way to fill requirement 2 of an approach.  
If an approach doesn't explicitly refer to this section, this is not used.  
This option does not work for Java source code.

A way to grant access to delegate identities would be to expose the invisible fields, making them accessible as properties. However, this solution is not ideal:
* How to name the delegate properties? Their names might clash with existing properties.
* Do we want invisible fields to be a part of the language? The invisible fields emitted by the old behaviour aren't officially documented (correct me if I'm wrong).
* If proposal 1 is accepted, this is irrelevant as there are no invisible fields in the new behaviour

This is why we propose an intrinsic accessor function that can be used to access delegate objects, known as the global accessor function, whose declarion can be seen below:

```kotlin
@kotlin.internal.InlineOnly
/**
 * @param R Receiver type that uses Implementation By Delegation for interface I
 * @param I The interface type that is delegated to the desired delegate
 * @return The delegate of the receiver for interface I
 */
inline fun <reified R : Any, reified I : Any> R.delegate(): I =
    throw NotImplementedError("Implementation of delegate function is intrinsic")
```

See doc comment for some specifics.
The compiler will emit bytecode specific to the type parameters, and that bytecode should be used to determine if the function is accessible or not.  
As a general rule of thumb, it should only be accessible within the class scope, but rules for accessing `protected` members are slightly more lenient than that.   
The compiler should emit an error if the given type isn't being delegated.

#### JVM Codegen
The compiler should emit bytecode for new a function to fill the task of the delegate accessor function for that interface.
It will contain the bytecode for the *Delegate Expression*. Its name will always be `delegate$accessor`.
A template for its signature could be as follows: `protected final fun delegate$accessor(): T`  
It is `protected` to allow access by subclasses. It is `final` to allow the JVM to inline it.

Name collisions are not a problem, because in JVM bytecode, there can be method overloads for descriptors that only differentiate themselves by return type.  
The method should be synthetic to avoid issues in Java source code when subclassing a kotlin delegating class.

For every invocation of the global delegate accessor function, whose declaration is described in detail under *Approach*, the compiler should:
* Check that the interface type `<I>` is being delegated by the receiver type `<R>`, emitting an error if this is not the case.
* If the enclosing class uses the old behaviour, emit a `getfield` instruction, referring to the field used to store the delegate for type `<I>`.
* Else, emit a `invokevirtual` instruction, referring to the delegate accessor function for the type `<I>` as detailed above.
* Check that the emitted bytecode does not violate access restrictions.

## Additional notes

### Class Construction
This note may be trivial, but there may be times before construction of an instance was completed, 
during which invocations of the interface methods produce undefined behaviour. This is already the case currently as the
invisible fields cannot be initialized before the superclass constructor has been called. When the restrictions are removed,
the programmer will gain control of the exact order in which things are initialized with respect to delegated interfaces.

## Open Discussion
* Which of the approaches should be used?
* Lexer/Parser implications if a new syntax is to be introduced
