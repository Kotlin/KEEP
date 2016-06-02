# Destructuring in Lambda Parameters

* **Type**: Design proposal
* **Author**: Andrey Breslav
* **Status**: Under consideration

## Goal

Support destructuring declarations in parameters of lambdas.

NOTE: Support in parameters of functions (declarations and expressions), constructors and setters will be discussed for future-proof design, but is not proposed to be implemented at this time.

## Use cases

To be supported now:

``` kotlin
// decomposing pairs in a lambda
listOfPairs.map {
  (a, b) -> a + b
}
```

May be supported later:

``` kotlin
// decompose a parameter:
fun foo((a, b): Pair<Int, String>, c: Bar)
// can be called as
foo(pair, bar)

// decompose a constructor parameter
class C(val (a, b): Pair<Int, String>) {}
```

## Syntax

We allow parentheses in lambda parameter lists:

In Kotlin 1.0 the syntax for a lambda is:

```
functionLiteral
  : "{" statements "}"
  : "{" (SimpleName (":" type)?){","} "->" statements "}"
  ;
```

Here we propose to extend it:

```
functionLiteral
  : "{" statements "}"
  : "{" lambdaParameter{","} "->" statements "}"
  ;
  
lambdaParameter
  : variableDeclarationEntry
  : multipleVariableDeclarations (":" type)? 
  ;
  
// already defined, shown for reference
  
variableDeclarationEntry
  : SimpleName (":" type)?
  ;

multipleVariableDeclarations
  : "(" variableDeclarationEntry{","} ")"
  ;  
```

> Note: while modifiers in front of lambda parameters are mentioned in the grammar for 1.0, they are not actually supported by the parser.
> If we ever support modifiers here, we should allow them in front of individual components, but for now, the actual rule should be without them  

Examples:

``` kotlin
{ a -> ... } // one parameter
{ a, b -> ... } // two parameters
{ (a, b) -> ... } // a destructured pair
{ (a, b), c -> ... } // a destructured pair and another parameter
```

> Question: Should we support nested destructuring for parameters?
> ```
{ ((a, b), c) -> ... } // a destructured pair whose first component is a pair
```
> NOTE: this is not supported for destructuring val's and var's 

A type may be specified for the whole destructured parameter, for its individual components (independently), or both: 

``` kotlin
{ (a, b: B) -> ... }
{ (a, b): Pair<A, B> -> ... }
{ (a: A, b): Pair<A, B> -> ... }
```

> Note: no changes to the syntax of function types is needed since the syntax for call sites (where a lambda is invoked) is not changed.
  
### Semantics and checks

Semantically, destructured parameters work as if they are normal parameters and a destructuring assignments are performed before the body of the lambda, in the left-to-right order:
  
``` kotlin
{ 
  (a, b), (c, d) -> 
  body() 
}
```

is translated to

```
{ 
  _p1, _p2 ->
  val (a, b) = _p1
  val (c, d) = _p2 
  body() 
}
```

**Typing rule**: Types of the components must be compatible with component-functions (`componentN()`) resolvable on corresponding parameters. When such functions can not be applied or their return types are not assignable to declared types of components, an error is reported.
   
Component-functions are resolved in the scope that contains the lambda, i.e. this should not compile:
   
``` kotlin
{
  component1: P.() -> A,
  component2: P.() -> B,
  (a, b): P // error: component functions not found 
  -> 
  ...  
```

When type inference is concerned with the shape of a lambda, a destructured parameter acts as a normal one (single), and its type may be used if declared. Types of components do not affect type inference even when specified: there's no way to know what type would be destructured into the given component types. 

**Naming rule**: all names declared in a parameter list of the same lambda (normal parameters and components of destructured ones) must be unique, i.e. this is an error: `{ (a, b), a -> ... }`


## IDE changes

- Intention actions for lambda parameters:
  - introduce destructuring
  - collapse destructuring
- Inspection to detect a manual destructuring, e.g.:

``` kotlin
{
    val (a, b) = it
    ...
}
```
- + a quick-fix to replace this with `{ (a, b) -> ... }`
- Actions to transform a for-loop into map/fiter/... should now support transferring destructured variables between `for` and lambdas.
- Adjustments may be needed in *Change signature* for lambdas 