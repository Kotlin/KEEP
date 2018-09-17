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

Currently, Implementation By Delegation is limited in a number of ways:
* Delegate Expressions are evaluated once, on construction, no exceptions.
* On the JVM, the result of the Delegate Expression is kept in an invisible field, it cannot be accessed. 
* Changing the behaviour of individual methods requires delegation to a constructor parameter property to access the delegated instance.
* Delegate Expressions cannot refer to `this` instance at all.

Other constructs that aim to simplify the implementation of the Delegation/Decorator pattern,  
such as `ForwardingObject` and subclasses from guava, do not suffer from these limitations.
In the case of `ForwardingObject`, it declares a method `protected abstract Object delegate();` which
gives full control to the programmer. The returned Delegate Identity can be anything, cached or not, and it can refer to `this`.

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

## Approach

This proposal aims at preserving source backward compatibility. 
Any existing sources should keep working and not have their behaviour changed.
 
### Approach for proposal 1

We found 3 approaches:
* Modifying existing syntax
* Using different syntax
* Adding contextual indication  

In the examples bellow `A` is an interface and `b` is a property of type `A`.

#### I. Modifying Existing Syntax
1. Adding a modificator to indicate new behaviour 
  - `class B : A by val b`
  - `class B : A by volatile b`
1. Modifying the form of the delegation expression
  - `class B : A by ::b`
  - `class B : A by { b }`
   (this would clash with existing delegations of functional types!)

##### Pros
* Compliments the existing feature
* May be done without requiring deprecation of the old syntax 

##### Cons
* We add a distinct, new, feature to the language
* May require a deprecation strategy for for the old syntax
* Allows for a given class to use a mix of the two behaviours


#### II. Using Different Syntax
1. Using a different keyword for class delegation
  - `class B : A to b`
1. Declaring delegation inside the class body 
  - `A { ... delegate A to b ... }`
  - `A { ... implement A by b ... }`

##### Pros
* Syntactically backward compatible
* May be made as concise as the current syntax
* It's clearer to the compiler and programmer which behaviour is expected.  
* Syntax differs from the property delegation syntax and stops confusing newcomers with a concept of delegation having two totally different use cases

##### Cons
* We add a distinct, new, feature to the language
* May require a deprecation strategy for for the old syntax
* Allows for a given class to use a mix of the two behaviours (even if one is deprecated)
* Syntax differs from the property delegation syntax and doesn't highlight that those are similar concepts

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

##### Cons
* Backward compatibility is not preserved for users with custom `@NewInterfaceDelegates` annotation 
* An annotation is used to semantics of the language in a significant way

The *new behaviour* would be defined as such:
* The Delegate Expression, following the `by` keyword, is evaluated on every invocation of a delegated method.
* The Delegate Expression has `this` in its scope
* No invisible fields are generated
* The Delegate Expression cannot refer to constructor parameters, use class members instead (programmer should store the delegate if it should be stored)
* There are cases where the reuse of the same syntax is a problem (see *Deprecation* section)

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

For each delegated interface, the compiler should emit bytecode for a function to access its Delegate Identity.  
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

Something to consider is to allow overriding the Delegate Expression. 
If it's allowed, delegate functions should not be marked final unless the declaring class is final. 
It should also be considered that, if it's allowed, the `override` keyword would not be present with the syntax.

### Class Construction

This note may be trivial, but there may be times before construction of an instance was completed, 
during which invocations of the interface methods produce undefined behaviour. This is already the case currently as the
invisible fields cannot be initialized before the superclass constructor has been called. When the restrictions are removed,
the programmer will gain control of the exact order in which things are initialized with respect to delegated interfaces.

### Deprecation

If the ability to deprecate the old behaviour and phase it out over time, by emitting compiler warnings, is desirable,
the suggested implementation of using a class annotation is very awkward in that to not use the deprecated feature, you must add an annotation.  
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