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

The current behaviour of Implementation by delegation has a number of unnecessary limitations.  
There are also situations where behaviour might be unexpected.
To rectify this, we propose the following:

### Proposal 1

Add a way to change the behaviour of interface delegates for a class.
The new behaviour will:
* Never create an invisible / inaccessible / implicit field;
* Move the responsibility to store the delegate reference to the programmer, should they mean to store it
* Have `this` in the scope of the expression

### Proposal 2

Add a compiler-intrinsic way to access the delegate used for a given interface:  
`inline fun <reified T> Any.delegate(): T`  

## Glossary

| Term | Meaning |
| ---- | ------- |
| IBD  | Implementation by delegation, kotlin feature, subject of this KEEP |
| DI   | Delegate identity, delegate instance, the reference to the delegate object |
| DE   | Delegate expression, following `by` keyword, with the DI as its result |
| Current/Old behaviour | The current behaviour of IBD, at the time of creating the proposal |
| New behaviour | The behaviour of IBD as proposed and defined at the bottom of *Implementation* section 

## Motivation

Kotlin provides IBD as a no-boilerplate way of implementing the Delegation/Decorator pattern,
which is a flexible alternative to implementation inheritance ([see doc](https://kotlinlang.org/docs/reference/delegation.html)).

Currently, IBD is unnecessarily limited in a number of ways:
* DEs are evaluated once, on construction, no exceptions.
* On the JVM, the result of the DE is kept in an invisible field, it cannot be accessed. 
* Changing the behaviour of individual methods requires delegation to a constructor parameter property to access the delegated instance.
* DEs cannot refer to `this` instance at all.

Other constructs that aim to simplify the implementation of the Delegation/Decorator pattern,  
such as `ForwardingObject` and subclasses from guava, do not suffer from these limitations.
In the case of `ForwardingObject`, it declares a method `protected abstract Object delegate();` which
gives full control to the programmer. The returned DI can be anything, cached or not, and it can refer to `this`.

### Use cases

Any case where the programmer would want to:
* Access the DI (proposal 2)
* Implement how to store the DI
* Change the DI
* Compute the DI on every invocation
* Refer to `this` instance in the DE
* Use IBD in inline classes  
This doesn't work in the old behaviour because it implicitly adds an (invisible) field, making the class exceed the one property constraint.

### Motivation for proposal 2

With the old behaviour of IBD, the DI is stored in an invisible field.
This invisible field cannot be accessed normally by the programmer, however, there are many cases where the programmer would need the DI:
* When overriding the behaviour of a delegated method, but still delegating to the same object.  
Especially if the delegate is a stateful object, it is absolutely necessary to have the DI.
* Numerous other reasons...

In order to access the DI, with the old behaviour:
* The DI must be passed to the primary constructor as a parameter, as only primary constructor parameters are accessible within the scope of DEs.
* The parameter holding the DI must be stored in an explicitly declared property (by declaring the parameter as a property or storing it elsewhere explicitly).

This means that:
* The class itself does not have any control over how the delegate is instantiated, unless the constructor parameter uses a default value.
* **It requires a primary constructor parameter!!**.
* 2 distinct fields are used to store the delegate.
* If the property storing a delegate reference is mutable, mutating it does not change the DI, but the programmer might think it does.
* The delegate can never have a reference to `this`, the delegating object, on instantiation.
* Code that wants to instantiate the class needs to pass the delegate itself to the constructor.
This is frequently the intended, but not always. Workarounds include: Secondary constructor, companion object invoke() overload.

I want to stress that this is NOT a problem for existing binaries.  
The delegate instance should NOT be made accessible outside the class scope through whichever solution, as it would break encapsulation,
and code that uses existing binaries is implicitly outside the class scope. 

A way to fix this issue would be to expose the invisible fields, making them accessible as properties. This poses problems:
* How to name the delegate properties? Their names might clash with existing properties.
* Do we want invisible fields to be a part of the language? The invisible fields emitted by the old behaviour aren't officially documented (correct me if I'm wrong).

This is why I propose a special `inline fun <reified T> Any.delegate()` function. 
It should be accessible as an extension function of `this` object only, i.e. its behaviour is as if it were a `protected final` class member.
Its implementation should be intrinsic. Different code should be emitted depending on the type parameter.  
The compiler should emit an error if the given type isn't being delegated.

## Implementation

Now that you're aware of all the problems with the old behaviour, let's discuss how they can be fixed with the new behaviour.
There has been a number of discussions about this as seen in the Links section.

Obviously, we require backward compatibility with the old behaviour. Any existing sources using it must not have their behaviour changed.

There are 2 approaches:
* Using a different syntax
* Indicating that the class wishes for the compiler to use the new (or proposed) behaviour by using a class annotation

I do not recommend the different syntax approach. I think the other approach is a much better one. Here's why the addition of a different syntax is a bad idea:

A number of different syntaxes have been mentioned (`A` is an interface and `b` is a property of type `A`):
* `class B : A by val b`, `class B : A by volatile b`, `class B : A to b`
* `class B : A by ::b`, `class B : A by { b }` (this would clash with existing delegations of functional types!)
* Declaring delegation inside the class body using some keyword: `delegate A to b` or `implement A by b`
* Declaring an annotation on the class member providing the delegate

There has not yet been a really nice idea for a different syntax (in my opinion).  
I think the problem is that a different syntax means we add a distinct, new, feature to the language.  
It will be a confusing feature, too, as it seemingly performs the same task as the existing IBD.  
It also allows for a given class to use a mix of the two behaviours, something which would definitely be very confusing and just bad design.

So let's talk about the second approach - using a class annotation - which is what this KEEP is proposing

This approach allows the programmer to indicate to the compiler that the class should have its interface delegates implemented using the new behaviour.
To indicate this, an annotation should be used. For example, the following declaration can be added to the standard library (name TBD):

```kotlin
@Target(CLASS, FILE)
@Retention(SOURCE)
public annotation class NewInterfaceDelegates
```

Reasons why this is a good idea:
* Doesn't add a new syntax/feature
* Backward compatibility is preserved
* Doesn't allow both behaviours to be present on a given class
* Least confusing of all solutions

Possible arguments against this idea:
* An annotation is used to change compiler output in a significant way (though annotations that affect compiler output already exist)

The *new behaviour* would be defined as such:
* The DE, following the `by` keyword, is evaluated on every invocation of a delegated method.
* The DE has `this` in its scope
* No invisible fields are generated
* The DE cannot refer to constructor parameters, use class members instead (programmer should store the delegate if it should be stored)

## JVM Codegen

The below only applies for classes where the new behaviour is enabled by the programmer.

For each delegated interface, the compiler should emit bytecode for a function to access its DI.  
It will contain the bytecode for the DE.
A template for its signature could be as follows: `protected final fun delegate$className(): T`  
For example: `protected final fun delegate$java$util$List(): java.util.List`
It is `protected` to allow access by subclasses. It is `final` to hint to the JVM that it can be inlined (optimization).

In JVM bytecode, there can be method overloads for descriptors that only differentiate themselves by return type.  
However, there might be a problem if the kotlin delegating class is subclassed by Java, where name collisions would be a problem
because the methods are going to be visible, unlike in Kotlin (if I didn't mention that, they should be invisible).  
If the `$` character makes a method invisible in Java, then we can just have the same name for all accessors as long as it has a `$` in it.  
If not, we just need an algorithm that prevents name collisions in the method name, for example by replacing `.` with `$` and any `$` with `$$` in the `className` part.

Then, for every invocation of `(protected) inline fun <reified T> Any.delegate()`, the compiler should:
* check that the type `T` is being delegated, emitting an error if this is not the case
* If the enclosing class uses the old behaviour, emit a `getfield` instruction, referring to the field keeping the delegate of the given type.
* Else, emit a `invokevirtual` instruction, referring to the delegate accessor function of the given type.

As for the actual method delegation, each interface method should be delegated to the DI as returned by a call to the accessor function for that interface.

## Reflection
Generated delegate accessor functions should not be returned as part of `KClass.members`, etc,
which is the same policy used for the generated, invisible, fields as generated by the old behaviour (On the JVM)

## JS

I don't know.

## Additional notes

### Overriding the DE

Something to consider is to allow overriding the DE. 
If it's allowed, delegate functions should not be marked final unless the declaring class is final. 
It should also be considered that, if it's allowed, the `override` keyword would not be present with the syntax.

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
