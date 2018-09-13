# Implementation by delegation enhancements

* **Type**: Design proposal
* **Author**: Dico Karssiens
* **Contributors**: []
* **Status**: Submitted
* **Prototype**: Not implemented, but I would be happy to do so

Discussion of this proposal is held in [this issue](https://github.com/Kotlin/KEEP/issues/153).  
Prior progress of this proposal is held in [this gist](https://gist.github.com/Dico200/0065293bcd9a19c50e371cde047a9f22).

##### Links

* [KT-83](https://youtrack.jetbrains.com/issue/KT-83)
* [KT-293](https://youtrack.jetbrains.com/issue/KT-293)
* [Suggestions for Interface delegation](https://discuss.kotlinlang.org/t/suggestions-for-interface-delegation/6624)
* [Unneeded limitations of delegated interfaces](https://discuss.kotlinlang.org/t/unneeded-limitations-of-delegated-interfaces/4535)
* [Accessing interface delegate](https://discuss.kotlinlang.org/t/accessing-interface-delegate/2068)
* [Delegate to class member](https://discuss.kotlinlang.org/t/delegate-to-class-member/711)

## Summary

### Proposal 1
Add a way to change the behaviour of interface delegates for a class.
The new behaviour will:
* Never create an invisible / inaccessible / implicit field;
* Move the responsibility to store the delegate reference to the programmer, should they mean to store it
* Have `this` in the scope of the expression
The current behaviour has a number of unnecessary limitations, which is the reason why this KEEP exists.

### Proposal 2
Add a compiler-intrinsic way to access the delegate used for a given interface:  
`inline fun <reified T> Any.delegate(): T`  

## Glossary
| Term | Meaning |
| ---- | ------- |
| IBD  | Implementation by delegation, kotlin feature, subject of this KEEP |
| DI   | Delegate identity, delegate instance, the reference to the delegate object |
| DE   | Delegate expression, following `by` keyword, with the DI as its result |

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
* Use IBD in inline classes - this doesn't work currently because IBD implicitly adds an (invisible) field

### Motivation for proposal 2

With the way IBD is currently implemented, the DI is stored in an invisible field.
This invisible field cannot be accessed normally by the programmer, however, there are many cases where the programmer would need the DI:
* When overriding the behaviour of a delegated method, but still delegating to the same object.  
Especially if the delegate is a stateful object, it is absolutely necessary to have the DI.
* Numerous other reasons...

In order to access the DI, currently:
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
* Do we want invisible fields to be a part of the language? The behaviour of the invisible fields isn't currently officially documented (correct me if I'm wrong).

This is why I propose a special `inline fun <reified T> Any.delegate()` function. 
It should be accessible as an extension function of `this` object only, i.e. its behaviour is as if it were a `protected final` class member.
Its implementation should be intrinsic. Different code should be emitted depending on the type parameter.  
The compiler should emit an error if the given type isn't being delegated.

## Implementation

Now that you're aware of all the problems with the current implementation, let's discuss how it can be fixed.
There has been a number of discussions about this as seen in the Links section.

Obviously, we require backward compatibility with the old behaviour. Any existing sources using it must not have their behaviour changed.

There are 2 approaches:
* Using a different syntax
* Indicating that the class wishes for the compiler to use the new (or proposed) behaviour by using a class annotation

I do not recommend the different syntax approach. I think the other approach is a much better one. Here's why the addition of a different syntax is a bad idea:

A number of different syntaxes have been mentioned (`A` is an interface and `b` is a property of type `A`):
* `class B : A by val b`, `class B : A by volatile b` 
* `class B : A by ::b`, `class B : A by { b }` (this would clash with existing delegations of functional types!)
* Declaring delegation inside the class body using some keyword: `delegate A to b` or `implement A by b`
* Declaring an annotation on the class member providing the delegate

There has not yet been a really nice idea for a different syntax.  
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
For each delegated interface, the compiler should emit bytecode for a `protected final fun delegate$${T::class.simpleName}(): T` function to get its delegate.
This method will simply have the DE inlined.
Name clashes here are a non-issue: If the return type is not the same, it concerns another delegate. The method descriptor is different then, so there's no clash in the binaries.

Each method of the interface should be delegated to the DI, as returned by a call to this method (instead of the invisible field, which was used previously).

Regarding proposal 2:
For every invocation of `(protected) inline fun <reified T> Any.delegate()`, the compiler should:
* check that the type `T` is being delegated, emitting an error if this is not the case
* If the enclosing class uses the old behaviour, emit a `getfield` instruction, referring to the field keeping the delegate of the given type.
* Else, emit a `invokevirtual` instruction, referring to the delegate accessor function of the given type.

## Reflection
Generated delegate accessor functions should not be returned as part of `KClass.members`, etc,
which is the same policy used for the generated, invisible, fields as generated by the current behaviour (On the JVM)

## JS
I don't know.

## Additional notes

### Overriding the DE
Something to consider is to allow overriding the DE. 
If it's allowed, delegate functions should not be marked final unless the declaring class is final. 
It should also be considered that, if it's allowed, the `override` keyword would not be present with the syntax.

## Open Discussion
* None
