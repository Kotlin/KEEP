# Opening classes for frameworks

* **Type**: Design proposal
* **Author**: Andrey Breslav
* **Contributors**: Dmitry Jemerov, Sébastien Deleuze, Yan Zhulanow, Andy Wilkinson 
* **Status**: Under consideration
* **Prototype**: Not started

## Feedback

Discussion of this proposal is held in [this issue](TODO).

## Synopsis

Kotlin has classes and their members `final` by default. This has been causing debates that are summarized in [this forum post](https://discuss.kotlinlang.org/t/a-bit-about-picking-defaults/1418). While all the reasoning behind this default remains valid, there are use cases that tend to be painful, most notable are frameworks using CGLib proxies (e.g. Spring AOP, Mockito) and Gradle that decorates task classes at runtime.

We propose to enable a compilation strategy that does not make classes and their members `final` if they are marked with framework-specific annotations, such as `@Service`, `@Repository`, `@Bean`,  `@Configuration`. 

## References

- [KT-12149](https://youtrack.jetbrains.com/issue/KT-12149) Provide a way to avoid mandatory open qualifier for CGLIB proxies
- [KT-6256](https://youtrack.jetbrains.com/issue/KT-6256) Allow a class to be open by default
- A. Breslav, [A bit about picking defaults](https://discuss.kotlinlang.org/t/a-bit-about-picking-defaults/1418)
- [A forum thread on `final` vs `open` by default](https://discuss.kotlinlang.org/t/classes-final-by-default/166)
- [~~KT-10759~~](https://youtrack.jetbrains.com/issue/KT-10759) CGLiib proxy nulls out dependencies 
- [~~KT-11098~~](https://youtrack.jetbrains.com/issue/KT-11098) Add quick fix for "Spring @Configuration/@Component annotated classes or @Bean annotated methods should be open"

> There's a somewhat similar issue with default constructors and JPA: 
>- [Hibernate/JPA](http://stackoverflow.com/questions/32038177/kotlin-with-jpa-default-constructor-hell)
>- [Hibernate + Guice Persist](https://discuss.kotlinlang.org/t/jpa-guice-gotchas/425)  
>- [JPA](https://discuss.kotlinlang.org/t/feature-request-a-modifier-annotation-for-data-classes-to-provide-a-non-arg-constructor-on-jvm/1549/4)
>- [Sugar ORM](https://discuss.kotlinlang.org/t/using-sugar-orm-with-kotlin/439/4)
>
> And another one about [SAM conversions vs extension function types](https://youtrack.jetbrains.com/issue/KT-12848).

## Possible implementations

Essentially, we need to let the Kotlin compiler know that certain framework-specific annotations mean "make this class and all of its members `open`" 

### Meta-annotation

One option is to provide a meta-annotation in the Kotlin Standard Library, e.g. `@kotlin.Open`, as suggested [here](https://youtrack.jetbrains.com/issue/KT-12149#comment=27-1422592). Then, framework authors would have to annotate their annotation declarations with `@kotlin.Open`, which at least adds a dependency on `kotlin-stdlib` and thus is too intrusive.

### META-INF file

A framework could provide a text file with a list of such special annotations in the `META-INF` directory of its artifacts. As this does not add dependencies, this is less of a problem than the meta-annotation case.

Example (`META-INF/kotlin-open-annotations.txt` - file name is subject to discussion):

```
org.springframework.context.annotation.Bean
org.springframework.context.annotation.Configuration
org.springframework.stereotype.Service
org.springframework.stereotype.Repository
```

- **pro**: even if the framework vendor does not include such metadata with the original artifacts, one can add a JAR containing only this file on the class path, and it will work.
- **con?**: will duplicate file names in the full list of entries be an issue here?
- **con**: the compiler will have to scan the entire class path to collect the list of all such annotations.
- **con**: an ad hoc file format for a very narrow issue
  - may be generalized to a proper module configuration file: default imports, module name, language level, etc.
 
### Compiler option
 
A list of special annotations can be passed as a compiler option (supported in CLI and build tools).
 
- **con**: this options would have to be specified again and again for each project. 
  - This can be mitigated to some extent by having something like `kotlin-maven-spring` plugin that adds the appropriate configuration to the standard Kotlin plugin for Maven/Gradle. 

### Compiler plugin

We could make framework-specific compiler plugins that register the appropriate annotation lists. The plugins themselves would be rather simple. They can be configured with one line in a build file (Maven/Gradle).
  
- **pro**: very local solution for a rather local problem
- **pro**: no changes in the CLI, i.e. it's not a big compiler features that deserves a whole command line switch
- **con**: the compiler plugin API that enables such plugins is public API nevertheless (although very generic), so it has to be maintained more or less indefinitely
 
> This can be combined with the "Compiler option" approach: compiler plugins can contribute options. Then, there will be only one plugin (provided by us), that can use unstable internal APIs, but its command line interface will be stable. 
  
## Rules and semantics

- A class marked with an opening annotation becomes `open` unless it's explicitly marked `final`.
  - what about `data` classes? 
- Members of such classes become `open` unless explicitly marked `final`
  - what about auto-generated members in `data` classes, such as `copy()`, that are final?
  > Entity classes cannot have the [data] annotation, because that generates a final copy() method EVEN if the class is marked open. If there are any final methods at all, the magic seems to fail. (From [here](https://discuss.kotlinlang.org/t/jpa-guice-gotchas/425).)


> An alternative approach would be to treat these members as `final` at compile time, but emit them as `open` in the byte code, but then many analyses that rely on finality will break, e.g. smart casts.  

## Alternative approaches

#### Modifier: `allopen`

We could abandon the annotation based approach altogether and add a special modifier, `allopen`, to the language, that makes the class and all of its members `open` by default. 
 
- **con**: in practice, the vast majority of call sites that have `allopen` will also have a framework-specific annotation, such as `@Service`. This looks like a lot of ceremony.  

#### Design guidelines

A program can be designed so that mocking/proxying of concrete classes is not needed (e.g. using interfaces).

#### JarOpener

A post-processor that flips flags in the byte code: [JarOpener](https://discuss.kotlinlang.org/t/classes-final-by-default/166/39)

## Open questions

-

## Arguments against this proposal

- This is not like anything we've done before: no meta-annotation mechanism has any such effect anywhere in Kotlin
- This looks a bit ad hoc
