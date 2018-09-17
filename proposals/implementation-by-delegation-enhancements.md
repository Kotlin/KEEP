# Implementation by delegation enhancements
* **Type**: Design proposal
* **Author**: Dico Karssiens
* **Contributors**: []
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
To rectify this, we propose the following:

### Proposal 1
Add a way to change the behaviour of interface delegates for a class.
The new behaviour will:
* Never create an invisible / inaccessible / implicit field;
* Move the responsibility to store the delegate reference to the programmer, should they mean to store it
* Have `this` in the scope of the expression

### Proposal 2
Add a compiler-intrinsic way to access the delegate identity used by a given object for a given interface which does not violate encapsulation constraints.

## Glossary
| Term | Meaning |
| ---- | ------- |
| Implementation By Delegation | kotlin feature, subject of this KEEP |
| Delegate Identity | delegate instance, the reference to the delegate object |
| Delegate Expression | Delegate expression, following `by` keyword, with the Delegate Identity as its result |
| Current/Old behaviour | The current behaviour of Implementation By Delegation, at the time of creating the proposal |
| New behaviour | The behaviour of Implementation By Delegation as proposed and defined at the bottom of *Approach* section 

## Motivation

### Motivation for proposal 1
Kotlin provides Implementation By Delegation as a no-boilerplate way of implementing the Delegation/Decorator pattern,
which is a flexible alternative to implementation inheritance ([see doc](https://kotlinlang.org/docs/reference/delegation.html)).

At the time when that feature was added, it was implemented such that:
* Delegate Expressions are evaluated once, on construction, no exceptions.
* On the JVM, the Delegate Identity is stored in an invisible field, where it cannot be accessed by the programmer. 
* Overriding individual delegated methods requires using a constructor parameter property to access the Delegate Identity.
* Delegate Expressions cannot refer to `this` instance at all. Instead, they can only access constructor parameters on top of outer scope.

These are considerable limitations which are not necessary, with the side effect of making certain things very difficult or obnoxious to do.

There are other constructs that aim to simplify implementation of the Delegation/Decorator pattern:
* [`ForwardingObject`](https://google.github.io/guava/releases/19.0/api/docs/com/google/common/collect/ForwardingObject.html) and subclasses from guava;  
it does not suffer from these limitations as it declares a method `protected abstract Object delegate();` which grants full control with respect to if and how it's cached.
* [`@Delegate`](https://projectlombok.org/features/Delegate.html) annotation from project lombok;  
which declares the delegation inside of the class scope, on a JVM field, and grants the same control, except that it can't run code upon access.

#### Use cases
Any case where the programmer would want to:
* Access the Delegate Identity (proposal 2)
* Implement how to store the Delegate Identity
* Change the Delegate Identity
* Compute the Delegate Identity on every invocation
* Refer to `this` instance in the Delegate Expression
* Use Implementation By Delegation in inline classes  
This doesn't work in the old behaviour because it implicitly adds an (invisible) field, making the class exceed the one property constraint.

### Motivation for proposal 2
With the old behaviour of Implementation By Delegation, the Delegate Identity is stored in an invisible field.
This invisible field cannot be accessed normally by the programmer, however, there are many cases where the programmer would need the Delegate Identity:
* When overriding the behaviour of a delegated method, but still delegating to the same object.  
* When controlling the state of the delegate if it is a stateful object.
* Using the delegate identity in a context where it's just an object with a type.

#### Current Workaround
In order to access the Delegate Identity, with the old behaviour:
* The Delegate Identity must be passed to the primary constructor as a parameter, as only primary constructor parameters are accessible within the scope of Delegate Expressions.
* The parameter holding the Delegate Identity must be stored in an explicitly declared property (by declaring the parameter as a property or storing it elsewhere explicitly).

This means that:
* The class itself does not have any control over how the delegate is instantiated, unless the constructor parameter uses a default value.
* **It requires a primary constructor parameter!!**.
* 2 distinct fields are used to store the delegate.
* If the property storing a delegate reference is mutable, mutating it does not change the Delegate Identity, but the programmer might think it does.
* The delegate can never have a reference to `this`, the delegating object, on instantiation.
* Code that wants to instantiate the class needs to pass the delegate itself to the constructor.
This is frequently the intended, but not always. Workarounds include: Secondary constructor, companion object invoke() overload.

There should be a language construct to get the delegate identity for a given delegated interface.  
It is accessible to the generated delegating interface methods, it should also be accessible in the source code as a solution to these problems.

I want to stress that this is NOT a problem for existing binaries.  
The delegate instance should NOT be made accessible outside the class scope through whichever solution, as it would break encapsulation,
and code that uses existing binaries is implicitly outside the class scope. 

If an approach for proposal 1 is chosen that exposes the Delegate Identity (such as approach II), it shouldn't be necessary to add this (proposal 2).

## Approach
This proposal aims at preserving source backward compatibility. 
Any existing sources should keep working and not have their behaviour changed.
 
### Approach for proposal 1
We found 3 approaches:
* Using different syntax
* Moving declaration of delegate inside the class body
* Adding contextual indication  

In the examples below, `target` refers to a property of class `A`

#### I. Using Different Syntax
1. Adding a contextual keyword to indicate new behaviour
    - `class Proxy : List<Int> by val target` (may be expected to declare a new property that stores `target`)
    - `class Proxy : List<Int> by volatile target` (ambiguous meaning of `volatile`)
1. Using a different keyword
    - `class Proxy : List<Int> to target`
    
##### Pros
* Syntactically backward compatible
* It's clear to the compiler and programmer which behaviour is expected
* In the case of [2], syntax differs from the property delegation syntax and doesn't highlight that those are similar concepts
* Old syntax can be deprecated, if that's desired

##### Cons
* We add a new language feature, instead of reusing old syntax
* Allows for a given class to use a mix of the two behaviours (even if one is deprecated)

#### II. Moving declaration inside class body
by adding a contextual keyword to indicate that a property is a `delegate` of its interface type:

```kotlin
class Proxy(target: List<Int>): List<Int> { 
    delegate val listDelegate: List<Int> = target
}
```

##### Pros 
* **Delegation is declared inside the class body**, a much more sensible place because:
    - Delegation is an implementation detail
    - It's where you always have `this` in the scope
* Very simple and intuitive
* Same semantics as regular kotlin properties
* Inheritance of the delegate property also has the same semantics as regular kotlin properties, 
overriding the delegate expression is simple, intuitive and explicit.
* Proposal 2 (the delegate accessor function) becomes obsolete (except for the old behaviour), as accessing the delegate is intuitive with a property.
* Doesn't litter the class declaration line as much
* Old syntax can be deprecated, if that's desired

##### Cons
* We add a new language feature, instead of reusing old syntax
* Allows for a given class to use a mix of the two behaviours (even if one is deprecated)
* Confusing with property delegates? They are still a completely different concept...
* Repeats a type instead of referring to an identifier

The `delegate` contextual keyword:
* Does not interfere with the property declaration at all, with the exception that it might introduce a type restriction.
* In other words, the property can still be `inline`, `abstract`, `private`, mutable, be volatile, use a property delegate, and/or declare a getter/setter. You get the point.
* Is a lot like [`@Delegate`](https://projectlombok.org/features/Delegate.html) annotation from project lombok,  
with slightly different semantics and less complexity 

Possible type policies of a `delegate` property include:
1. the type is restricted to the interface types implemented by the class (mimicking old behaviour's effective policy)
2. the type is not restricted, and all its public members, except for those declared in `Any`, are delegated

The policy for delegated interface member collisions should probably be as follows:
* If the colliding member is from the supertype, and it is final, the delegation is illegal
* Otherwise, it should be overridden.
* Else (if the member is declared in the same class), that member takes priority
* And the programmer can use `super` call to prevent the delegate overriding the behavior of a member from the supertype.

#### III. Adding Contextual Indication
This approach allows the programmer to indicate to the compiler that the class should have its interface delegates implemented using the new behaviour.
To indicate this, an annotation should be used. For example, the following declaration can be added to the standard library (name TBD):

```kotlin
@Target(CLASS, FILE)
@Retention(SOURCE)
public annotation class NewInterfaceDelegates
```

##### Pros
* Doesn't add a new syntax/feature
* Doesn't allow both behaviours to be present on a given class
* Doesn't break backward compatibility because the programmer explicitly changes behaviour by declaring the annotation

##### Cons
* An annotation is used to change behaviour and semantics of a language feature significantly
* Annotation needs to be present on all classes using the new behaviour unless a compiler argument is used, which could break backward compatibility

The *new behaviour* would be defined as such:
* The Delegate Expression, wherever it is declared, is evaluated on every invocation of a delegated method.
* The Delegate Expression has `this` in its scope
* No invisible fields are generated
* The Delegate Expression cannot refer to constructor parameters, use class members instead (programmer should store the delegate if it should be stored)
* There are cases where the reuse of the same syntax is a problem (see *Deprecation* section)

#### Dropped Ideas
* Modifying the form of the delegation expression
    - `class B : A by ::b`
    - `class B : A by { b }`
    - these would clash with existing delegations of functional types
    - compiler could behave differently when it infers type of `KProperty`, but this is implicit and confusing.

### Approach for proposal 2
A way to grant access to delegate identities would be to expose the invisible fields, making them accessible as properties. However, this solution is not ideal:
* How to name the delegate properties? Their names might clash with existing properties.
* Do we want invisible fields to be a part of the language? The invisible fields emitted by the old behaviour aren't officially documented (correct me if I'm wrong).
* If proposal 1 is accepted, this is irrelevant as there are no invisible fields in the new behaviour

This is why we propose an intrinsic accessor function that can be used to access delegate objects, whose declarion can be seen below:

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

## JVM Codegen

### Class members for classes with new behaviour
Each delegated interface type will be accompanied by an accessor function, called to access the Delegate Identity.

If the approach for proposal 1 is approach II (move declaration inside class body), this is the property getter.

Else, the compiler should emit bytecode for new a function to fill this task.  
It will contain the bytecode for the Delegate Expression.
A template for its signature could be as follows: `protected final fun delegate$className(): T`  
For example: `protected final fun delegate$java$util$List(): java.util.List`
It is `protected` to allow access by subclasses. It is `final` to hint to the JVM that it can be inlined (optimization).

In JVM bytecode, there can be method overloads for descriptors that only differentiate themselves by return type.  
However, there might be a problem if the kotlin delegating class is subclassed by Java, where name collisions would be a problem
because the methods are going to be visible, unlike in Kotlin (if I didn't mention that, they should be invisible).  
If the `$` character makes a method invisible in Java, then we can just have the same name for all accessors as long as it has a `$` in it.  
If not, we just need an algorithm that prevents name collisions in the method name, for example by replacing `.` with `$` and any `$` with `$$` in the `className` part.

As for the actual method delegation, each interface method should be delegated to the Delegate Identity as returned by a call to the accessor function for that interface.

### Invocations of delegate accessor
For every invocation of the delegate accessor function, whose declaration is described in detail under *Approach*, the compiler should:
* Check that the interface type `<I>` is being delegated by the receiver type `<R>`, emitting an error if this is not the case.
* If the enclosing class uses the old behaviour, emit a `getfield` instruction, referring to the field used to store the delegate for type `<I>`.
* Else, emit a `invokevirtual` instruction, referring to the delegate accessor function for the type `<I>` as detailed above.
* Check that the emitted bytecode does not violate access restrictions.

## Reflection
Generated delegate accessor functions should not be returned as part of `KClass.members`, etc,
which is the same policy used for the generated, invisible, fields as generated by the old behaviour (On the JVM)

## JS
I don't know.

## Additional notes

### Overriding the Delegate Expression
This is not a problem at all with `delegate val` approach.  
Something to consider is to allow overriding the Delegate Expression. 
If it's allowed, delegate accessor functions should not be marked final unless the declaring class is final. 
It should also be considered that, if it's allowed, the `override` keyword would not be present with the syntax.

### Class Construction
This note may be trivial, but there may be times before construction of an instance was completed, 
during which invocations of the interface methods produce undefined behaviour. This is already the case currently as the
invisible fields cannot be initialized before the superclass constructor has been called. When the restrictions are removed,
the programmer will gain control of the exact order in which things are initialized with respect to delegated interfaces.

### Deprecation
If the ability to deprecate the old behaviour and phase it out over time, by emitting compiler warnings, is desirable,
the approach of using a contextual indication is very awkward in that to not use the deprecated feature, you must add an annotation.  
To tackle this, a compiler argument can be used to enable the new behaviour for each class in the code base.
To allow the programmer to still use the old behaviour with this compiler argument, if it is decided that they should be able to, 
another annotation or an annotation parameter can be introduced.

The problem comes when JB want to switch from having a compiler argument to enable the new behaviour, 
to having a compiler argument to still support the old behaviour. In that case, we would want to emit a warning or error
when the old behaviour is still used, but the old and new behaviour use the same syntax, so the problem is how to know which
behaviour was used.  
The programmer might not have explicitly enabled the new behaviour when we want to do this, and we can't emit errors, 
so some code might compile with completely different behaviour without the programmer knowing.  
Because of this, I think it would be acceptable to support both behaviours indefinitely or until a solution to this problem is found.

## Open Discussion
* Should we prefer a separate syntax over an annotation and/or compiler argument?
* How to deprecate and phase out the old behaviour, if at all? (See Deprecation above)
* Lexer/Parser implications when a new syntax is to be introduced remain undiscussed